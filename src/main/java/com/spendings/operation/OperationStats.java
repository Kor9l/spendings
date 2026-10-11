package com.spendings.operation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Агрегаты по операциям для других модулей, в нужной валюте по курсу на дату операции. */
@Service
@Transactional(readOnly = true)
public class OperationStats {

  private final JdbcClient jdbc;

  OperationStats(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Чистое движение денег по каждому счёту за каждый месяц начиная с {@code from}: всё, что пришло,
   * минус всё, что ушло, в валюте {@code currency}. Строки без курса не учитываются.
   */
  public Map<UUID, Map<YearMonth, BigDecimal>> monthlyNet(
      List<UUID> accountIds, String currency, YearMonth from) {
    Map<UUID, Map<YearMonth, BigDecimal>> result = new HashMap<>();
    if (accountIds.isEmpty()) {
      return result;
    }
    jdbc.sql(
            """
            select l.account_id, to_char(o.occurred_on, 'YYYY-MM') as month,
                   sum(l.amount * fx_rate(a.currency, :currency, o.occurred_on)) as net
            from operation o
            join operation_line l on l.operation_id = o.id
            join account a on a.id = l.account_id
            where o.status = 'CLEARED' and l.account_id in (:accounts) and o.occurred_on >= :from
            group by 1, 2
            """)
        .param("currency", currency)
        .param("accounts", accountIds)
        .param("from", from.atDay(1))
        .query(
            (rs, i) -> {
              BigDecimal net = rs.getBigDecimal("net");
              if (net != null) {
                result
                    .computeIfAbsent(rs.getObject("account_id", UUID.class), k -> new HashMap<>())
                    .put(YearMonth.parse(rs.getString("month")), net);
              }
              return null;
            })
        .list();
    return result;
  }

  /**
   * Средний расход в месяц за последние {@code months} полных месяцев, в валюте {@code currency}.
   * Если учёт ведётся меньше, делится на число месяцев с первой операции (минимум 1).
   */
  public BigDecimal averageMonthlyExpenses(String currency, int months, YearMonth now) {
    YearMonth first = now.minusMonths(months);
    LocalDate from = first.atDay(1);
    LocalDate to = now.minusMonths(1).atEndOfMonth();
    BigDecimal total =
        jdbc.sql(
                """
                select coalesce(-sum(l.amount * fx_rate(a.currency, :currency, o.occurred_on)), 0)
                from operation o
                join operation_line l on l.operation_id = o.id
                join account a on a.id = l.account_id
                where o.status = 'CLEARED' and o.type = 'EXPENSE'
                  and o.occurred_on between :from and :to
                """)
            .param("currency", currency)
            .param("from", from)
            .param("to", to)
            .query(BigDecimal.class)
            .single();
    LocalDate firstOperation =
        jdbc.sql("select min(occurred_on) from operation where type = 'EXPENSE'")
            .query(LocalDate.class)
            .optional()
            .orElse(null);
    long divisor = months;
    if (firstOperation != null && firstOperation.isAfter(from)) {
      divisor =
          Math.max(
              1, YearMonth.from(firstOperation).until(now, java.time.temporal.ChronoUnit.MONTHS));
    }
    return total.divide(BigDecimal.valueOf(divisor), 2, java.math.RoundingMode.HALF_UP);
  }
}
