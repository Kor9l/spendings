package com.spendings.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Отчётные запросы. Суммы пересчитываются в базовую валюту по курсу на дату операции через SQL-
 * функцию {@code fx_rate}. Если курса нет, строка не попадает в сумму, а учитывается в {@code
 * missingRates} — отчёт показывает предупреждение.
 */
@Component
class ReportQueries {

  /** Строки проведённых расходов и доходов с курсом к базовой валюте. */
  private static final String LINES =
      """
      from operation o
      join operation_line l on l.operation_id = o.id
      join account a on a.id = l.account_id
      cross join lateral (select fx_rate(a.currency, :base, o.occurred_on) as rate) fx
      where o.status = 'CLEARED' and o.type in ('EXPENSE', 'INCOME')
        and o.occurred_on between :from and :to
      """;

  private final JdbcClient jdbc;

  ReportQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Доходы и расходы за период; расходы — положительным числом. */
  Totals totals(LocalDate from, LocalDate to, String base) {
    var totals = new Totals[] {new Totals(BigDecimal.ZERO, BigDecimal.ZERO, 0)};
    jdbc.sql(
            "select o.type, coalesce(sum(l.amount * fx.rate), 0) as total,"
                + " count(*) filter (where fx.rate is null) as missing "
                + LINES
                + " group by o.type")
        .param("base", base)
        .param("from", from)
        .param("to", to)
        .query(
            (rs, i) -> {
              BigDecimal total = rs.getBigDecimal("total");
              int missing = rs.getInt("missing");
              Totals t = totals[0];
              totals[0] =
                  "INCOME".equals(rs.getString("type"))
                      ? new Totals(total, t.expenses(), t.missingRates() + missing)
                      : new Totals(t.income(), total.negate(), t.missingRates() + missing);
              return null;
            })
        .list();
    return totals[0];
  }

  /**
   * Суммы по конечным категориям (той, что в строке операции) за период, положительными числами.
   */
  Map<UUID, BigDecimal> byCategory(String type, LocalDate from, LocalDate to, String base) {
    var result = new LinkedHashMap<UUID, BigDecimal>();
    jdbc.sql(
            "select l.category_id, sum(abs(l.amount) * fx.rate) as total "
                + LINES
                + " and o.type = :type and fx.rate is not null group by l.category_id")
        .param("base", base)
        .param("from", from)
        .param("to", to)
        .param("type", type)
        .query(
            (rs, i) ->
                result.put(rs.getObject("category_id", UUID.class), rs.getBigDecimal("total")))
        .list();
    return result;
  }

  /**
   * Доходы и расходы по месяцам, начиная с {@code first}; при заданных категориях — только по ним
   * (категория вместе с подкатегориями передаётся списком).
   */
  List<MonthTotals> monthly(YearMonth first, YearMonth last, String base, List<UUID> categories) {
    String categoryFilter = categories.isEmpty() ? "" : " and l.category_id in (:categories)";
    var spec =
        jdbc.sql(
                "select to_char(o.occurred_on, 'YYYY-MM') as month,"
                    + " coalesce(sum(l.amount * fx.rate) filter (where o.type = 'INCOME'), 0) as income,"
                    + " coalesce(-sum(l.amount * fx.rate) filter (where o.type = 'EXPENSE'), 0) as expenses "
                    + LINES
                    + categoryFilter
                    + " group by 1 order by 1")
            .param("base", base)
            .param("from", first.atDay(1))
            .param("to", last.atEndOfMonth());
    if (!categories.isEmpty()) {
      spec = spec.param("categories", categories);
    }
    Map<String, MonthTotals> byMonth = new LinkedHashMap<>();
    spec.query(
            (rs, i) ->
                byMonth.put(
                    rs.getString("month"),
                    new MonthTotals(
                        YearMonth.parse(rs.getString("month")),
                        rs.getBigDecimal("income"),
                        rs.getBigDecimal("expenses"))))
        .list();
    // месяцы без операций — нулями, чтобы на графике не было дыр
    var months = new ArrayList<MonthTotals>();
    for (YearMonth m = first; !m.isAfter(last); m = m.plusMonths(1)) {
      months.add(
          byMonth.getOrDefault(m.toString(), new MonthTotals(m, BigDecimal.ZERO, BigDecimal.ZERO)));
    }
    return months;
  }

  /**
   * Капитал на конец каждого месяца: остатки счетов, учитываемых в капитале, по курсу на ту дату
   * (для текущего месяца — на сегодня). Суммы без курса не входят.
   */
  List<MonthCapital> capital(YearMonth first, YearMonth last, String base) {
    return jdbc.sql(
            """
            with months as (
              select (date_trunc('month', m) + interval '1 month - 1 day')::date as month_end
              from generate_series(cast(:first as date), cast(:last as date), interval '1 month') m
            ),
            -- движения по счетам один раз: сумма за месяц, потом нарастающим итогом
            monthly as materialized (
              select l.account_id,
                     (date_trunc('month', o.occurred_on) + interval '1 month - 1 day')::date as month_end,
                     sum(l.amount) as amount
              from operation_line l
              join operation o on o.id = l.operation_id
              where o.status = 'CLEARED'
              group by 1, 2
            ),
            balances as (
              select m.month_end, a.type, a.currency,
                     a.opening_balance + coalesce((
                       select sum(x.amount) from monthly x
                       where x.account_id = a.id and x.month_end <= m.month_end
                     ), 0) as balance
              from months m
              join account a on a.in_net_worth and a.opened_on <= m.month_end
            )
            select month_end,
                   coalesce(sum(balance * fx_rate(currency, :base, least(month_end, current_date))), 0) as total,
                   coalesce(sum(balance * fx_rate(currency, :base, least(month_end, current_date)))
                            filter (where type in ('DEPOSIT', 'SAVINGS')), 0) as savings
            from months m left join balances b using (month_end)
            group by month_end
            order by month_end
            """)
        .param("first", first.atDay(1))
        .param("last", last.atDay(1))
        .param("base", base)
        .query(
            (rs, i) ->
                new MonthCapital(
                    YearMonth.from(rs.getObject("month_end", LocalDate.class)),
                    rs.getBigDecimal("total"),
                    rs.getBigDecimal("savings")))
        .list();
  }

  /**
   * @param savings из них на вкладах и накопительных счетах
   */
  record MonthCapital(YearMonth month, BigDecimal total, BigDecimal savings) {}

  record Totals(BigDecimal income, BigDecimal expenses, int missingRates) {

    public BigDecimal net() {
      return income.subtract(expenses);
    }
  }

  record MonthTotals(YearMonth month, BigDecimal income, BigDecimal expenses) {}
}
