package com.spendings.deposit;

import com.spendings.account.AccountRef;
import com.spendings.account.Accounts;
import com.spendings.account.Accounts.BalanceTimeline;
import com.spendings.account.Accounts.DepositRef;
import com.spendings.account.Capitalization;
import com.spendings.category.Categories;
import com.spendings.category.CategoryKind;
import com.spendings.category.CategoryRef;
import com.spendings.deposit.InterestSchedule.Period;
import com.spendings.fx.BaseCurrency;
import com.spendings.fx.ExchangeRates;
import com.spendings.operation.Operations;
import com.spendings.operation.RecordOperation.RecordIncome;
import com.spendings.operation.RecordOperation.Split;
import com.spendings.shared.DomainException;
import com.spendings.shared.NotFoundException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Вклады: график начислений по условиям и фактическому остатку, проведение процентов на фактическую
 * сумму, годовой отчёт. Ожидаемые начисления не хранятся — вычисляются из условий; в базе только
 * решения по ним.
 */
@Service
@Transactional(readOnly = true)
public class Deposits {

  /** Так называется категория дохода из шаблона; переименуют — проценты уйдут в «Не разобрано». */
  static final String INTEREST_CATEGORY = "Проценты по вкладам";

  private static final MathContext MC = MathContext.DECIMAL64;
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

  private final JdbcClient jdbc;
  private final Accounts accounts;
  private final Operations operations;
  private final Categories categories;
  private final ExchangeRates rates;
  private final BaseCurrency baseCurrency;

  Deposits(
      JdbcClient jdbc,
      Accounts accounts,
      Operations operations,
      Categories categories,
      ExchangeRates rates,
      BaseCurrency baseCurrency) {
    this.jdbc = jdbc;
    this.accounts = accounts;
    this.operations = operations;
    this.categories = categories;
    this.rates = rates;
    this.baseCurrency = baseCurrency;
  }

  /** Все вклады: действующие, потом архивные. */
  public List<DepositView> all() {
    LocalDate today = LocalDate.now();
    return accounts.deposits().stream().map(d -> view(d, today)).toList();
  }

  public DepositView view(UUID accountId) {
    return view(load(accountId), LocalDate.now());
  }

  /** Начисления, которые пора провести: период закончился, решения ещё нет. */
  public List<Due> due() {
    List<Due> due = new ArrayList<>();
    for (DepositView v : all()) {
      if (v.deposit().account().archived()) {
        continue;
      }
      for (Row r : v.rows()) {
        if (r.state() == State.DUE) {
          due.add(new Due(v.deposit(), r.period(), payoutAccount(v.deposit())));
        }
      }
    }
    due.sort((a, b) -> a.period().end().compareTo(b.period().end()));
    return due;
  }

  /** Провести проценты за период на фактическую сумму (её сообщает банк). */
  @Transactional
  public UUID post(UUID accountId, LocalDate periodEnd, BigDecimal amount, LocalDate date) {
    if (amount.signum() <= 0) {
      throw new DomainException("operation.error.amountPositive");
    }
    DepositRef deposit = load(accountId);
    Period period = period(deposit, periodEnd);
    AccountRef target = payoutAccount(deposit);
    String note = "Проценты за " + DAY.format(period.start()) + " – " + DAY.format(period.end());
    UUID operationId =
        operations.recordInterest(
            new RecordIncome(
                target.id(),
                date,
                List.of(new Split(interestCategory(), amount)),
                deposit.bank() == null ? deposit.account().name() : deposit.bank(),
                note));
    jdbc.sql(
            """
            insert into deposit_accrual (account_id, period_end, status, operation_id)
            values (?, ?, 'POSTED', ?)
            on conflict (account_id, period_end)
            do update set status = 'POSTED', operation_id = excluded.operation_id, resolved_at = now()
            """)
        .params(accountId, periodEnd, operationId)
        .update();
    return operationId;
  }

  /** Пропустить начисление: например, проценты уже пришли выпиской. */
  @Transactional
  public void skip(UUID accountId, LocalDate periodEnd) {
    period(load(accountId), periodEnd);
    jdbc.sql(
            """
            insert into deposit_accrual (account_id, period_end, status) values (?, ?, 'SKIPPED')
            on conflict (account_id, period_end) do nothing
            """)
        .params(accountId, periodEnd)
        .update();
  }

  /** Вернуть пропущенное начисление в ожидаемые. */
  @Transactional
  public void unskip(UUID accountId, LocalDate periodEnd) {
    jdbc.sql(
            "delete from deposit_accrual where account_id = ? and period_end = ? and status = 'SKIPPED'")
        .params(accountId, periodEnd)
        .update();
  }

  /** Проценты за год по вкладам и месяцам: полученные — по факту, остальные — по графику. */
  public YearReport year(int year) {
    String base = baseCurrency.code();
    LocalDate from = LocalDate.of(year, 1, 1);
    LocalDate to = LocalDate.of(year, 12, 31);
    LocalDate today = LocalDate.now();

    Map<UUID, BigDecimal[]> received = new HashMap<>();
    jdbc.sql(
            """
            select d.account_id, extract(month from o.occurred_on)::int as month,
                   sum(l.amount * fx_rate(a.currency, :base, o.occurred_on)) as amount
            from deposit_accrual d
            join operation o on o.id = d.operation_id
            join operation_line l on l.operation_id = o.id
            join account a on a.id = l.account_id
            where d.status = 'POSTED' and o.occurred_on between :from and :to
            group by 1, 2
            """)
        .param("base", base)
        .param("from", from)
        .param("to", to)
        .query(
            (rs, i) -> {
              BigDecimal amount = rs.getBigDecimal("amount");
              if (amount != null) {
                received
                        .computeIfAbsent(rs.getObject("account_id", UUID.class), k -> zeros())[
                        rs.getInt("month") - 1] =
                    amount;
              }
              return null;
            })
        .list();

    List<YearRow> rows = new ArrayList<>();
    BigDecimal[] monthTotals = zeros();
    BigDecimal totalReceived = BigDecimal.ZERO;
    BigDecimal totalExpected = BigDecimal.ZERO;
    for (DepositView v : all()) {
      UUID id = v.deposit().account().id();
      BigDecimal[] months = received.getOrDefault(id, zeros());
      BigDecimal got = BigDecimal.ZERO;
      for (int m = 0; m < 12; m++) {
        months[m] = scale(months[m]);
        got = got.add(months[m]);
        monthTotals[m] = monthTotals[m].add(months[m]);
      }
      BigDecimal expected = BigDecimal.ZERO;
      for (Row r : v.rows()) {
        if ((r.state() == State.FUTURE || r.state() == State.DUE)
            && r.period().end().getYear() == year) {
          expected =
              expected.add(
                  rates
                      .convert(r.period().net(), v.deposit().account().currency(), base, today)
                      .orElse(BigDecimal.ZERO));
        }
      }
      if (got.signum() == 0 && expected.signum() == 0) {
        continue;
      }
      BigDecimal yield = actualYield(v.deposit(), from, to.isBefore(today) ? to : today, year);
      rows.add(new YearRow(v.deposit(), months, scale(got), scale(expected), yield));
      totalReceived = totalReceived.add(got);
      totalExpected = totalExpected.add(expected);
    }
    for (int m = 0; m < 12; m++) {
      monthTotals[m] = scale(monthTotals[m]);
    }
    return new YearReport(
        year, base, rows, monthTotals, scale(totalReceived), scale(totalExpected));
  }

  // ---- внутреннее ----

  private DepositView view(DepositRef deposit, LocalDate today) {
    UUID id = deposit.account().id();
    BalanceTimeline timeline = accounts.timeline(id);
    // проценты, выплаченные на вклад, уже в остатке: база периода — остаток на его начало
    List<Period> periods =
        InterestSchedule.build(
            deposit.openedOn(),
            deposit.maturesOn(),
            deposit.annualRate(),
            deposit.capitalization(),
            deposit.taxRate(),
            timeline::at,
            today);
    Map<LocalDate, Resolved> resolved = resolved(id);

    List<Row> rows = new ArrayList<>();
    BigDecimal received = BigDecimal.ZERO;
    BigDecimal expected = BigDecimal.ZERO;
    for (Period p : periods) {
      Resolved r = resolved.get(p.end());
      State state;
      if (r != null) {
        state = r.posted() ? State.POSTED : State.SKIPPED;
        if (r.amount() != null) {
          received = received.add(r.amount());
        }
      } else if (p.end().isBefore(deposit.addedOn())) {
        state = State.BEFORE;
      } else if (!p.end().isAfter(today)) {
        state = State.DUE;
        expected = expected.add(p.net());
      } else {
        state = State.FUTURE;
        expected = expected.add(p.net());
      }
      rows.add(
          new Row(
              p,
              state,
              r == null ? null : r.amount(),
              r == null ? null : r.operationId(),
              r == null ? null : r.currency()));
    }
    BigDecimal balance = timeline.at(today);
    Long daysLeft =
        deposit.maturesOn() == null
            ? null
            : Math.max(0, ChronoUnit.DAYS.between(today, deposit.maturesOn()));
    BigDecimal atMaturity =
        deposit.maturesOn() == null
            ? null
            : deposit.capitalization() == Capitalization.NONE ? balance : balance.add(expected);
    return new DepositView(
        deposit,
        scale(balance),
        rows,
        scale(received),
        scale(expected),
        atMaturity == null ? null : scale(atMaturity),
        effectiveRate(deposit),
        daysLeft);
  }

  /** Ставка после налога с учётом капитализации: сколько вклад приносит за год на деле. */
  static BigDecimal effectiveRate(DepositRef deposit) {
    BigDecimal net =
        deposit.annualRate().multiply(HUNDRED.subtract(deposit.taxRate()), MC).divide(HUNDRED, MC);
    int perYear =
        switch (deposit.capitalization()) {
          case MONTHLY -> 12;
          case QUARTERLY -> 4;
          case NONE, AT_MATURITY -> 1;
        };
    if (perYear == 1) {
      return net.setScale(2, RoundingMode.HALF_UP);
    }
    BigDecimal growth =
        BigDecimal.ONE
            .add(net.divide(HUNDRED.multiply(BigDecimal.valueOf(perYear)), MC))
            .pow(perYear, MC);
    return growth.subtract(BigDecimal.ONE).multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP);
  }

  /** Фактическая доходность за год, % годовых: полученные проценты к среднему остатку. */
  private @Nullable BigDecimal actualYield(
      DepositRef deposit, LocalDate from, LocalDate to, int year) {
    LocalDate start = deposit.openedOn().isAfter(from) ? deposit.openedOn() : from;
    if (!to.isAfter(start)) {
      return null;
    }
    BalanceTimeline timeline = accounts.timeline(deposit.account().id());
    BigDecimal sum = BigDecimal.ZERO;
    long days = 0;
    for (LocalDate d = start; d.isBefore(to); d = d.plusDays(1)) {
      sum = sum.add(timeline.at(d));
      days++;
    }
    BigDecimal interest =
        jdbc.sql(
                """
                select coalesce(sum(l.amount), 0)
                from deposit_accrual d
                join operation o on o.id = d.operation_id
                join operation_line l on l.operation_id = o.id
                where d.account_id = :id and d.status = 'POSTED'
                  and o.occurred_on > :from and o.occurred_on <= :to
                """)
            .param("id", deposit.account().id())
            .param("from", start)
            .param("to", to)
            .query(BigDecimal.class)
            .single();
    BigDecimal average = sum.divide(BigDecimal.valueOf(days), MC);
    if (average.signum() <= 0 || interest.signum() == 0) {
      return null;
    }
    return interest
        .multiply(BigDecimal.valueOf(Year.of(year).length()), MC)
        .multiply(HUNDRED, MC)
        .divide(average.multiply(BigDecimal.valueOf(days), MC), 2, RoundingMode.HALF_UP);
  }

  private Map<LocalDate, Resolved> resolved(UUID accountId) {
    Map<LocalDate, Resolved> result = new LinkedHashMap<>();
    jdbc.sql(
            """
            select d.period_end, d.status, d.operation_id,
                   (select sum(l.amount) from operation_line l where l.operation_id = d.operation_id) as amount,
                   (select a.currency from operation_line l join account a on a.id = l.account_id
                     where l.operation_id = d.operation_id limit 1) as currency
            from deposit_accrual d
            where d.account_id = ?
            """)
        .param(accountId)
        .query(
            (rs, i) ->
                result.put(
                    rs.getObject("period_end", LocalDate.class),
                    new Resolved(
                        "POSTED".equals(rs.getString("status")),
                        rs.getObject("operation_id", UUID.class),
                        rs.getBigDecimal("amount"),
                        rs.getString("currency"))))
        .list();
    return result;
  }

  private Period period(DepositRef deposit, LocalDate periodEnd) {
    if (periodEnd.isAfter(LocalDate.now())) {
      throw new DomainException("deposit.error.notYet");
    }
    return view(deposit, LocalDate.now()).rows().stream()
        .map(Row::period)
        .filter(p -> p.end().equals(periodEnd))
        .findFirst()
        .orElseThrow(() -> new DomainException("deposit.error.noPeriod"));
  }

  private AccountRef payoutAccount(DepositRef deposit) {
    if (deposit.capitalization() == Capitalization.NONE && deposit.payoutAccountId() != null) {
      return accounts.find(deposit.payoutAccountId()).orElse(deposit.account());
    }
    return deposit.account();
  }

  private UUID interestCategory() {
    return categories.all().values().stream()
        .filter(
            c ->
                c.kind() == CategoryKind.INCOME
                    && c.parentId() == null
                    && !c.archived()
                    && INTEREST_CATEGORY.equals(c.name()))
        .map(CategoryRef::id)
        .findFirst()
        .orElseGet(() -> categories.uncategorized(CategoryKind.INCOME));
  }

  private DepositRef load(UUID accountId) {
    return accounts.deposit(accountId).orElseThrow(() -> new NotFoundException("Вклад"));
  }

  private static BigDecimal[] zeros() {
    BigDecimal[] months = new BigDecimal[12];
    java.util.Arrays.fill(months, BigDecimal.ZERO);
    return months;
  }

  private static BigDecimal scale(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_EVEN);
  }

  private record Resolved(
      boolean posted,
      @Nullable UUID operationId,
      @Nullable BigDecimal amount,
      @Nullable String currency) {}

  public enum State {
    /** До того, как вклад завели в приложении: проценты уже в начальном остатке. */
    BEFORE,
    POSTED,
    SKIPPED,
    DUE,
    FUTURE
  }

  /**
   * Строка графика.
   *
   * @param actual фактически зачисленная сумма (в валюте счёта зачисления)
   */
  public record Row(
      Period period,
      State state,
      @Nullable BigDecimal actual,
      @Nullable UUID operationId,
      @Nullable String actualCurrency) {

    public boolean actualDiffers() {
      return actual != null && actual.compareTo(period.net()) != 0;
    }
  }

  /**
   * Вклад с графиком.
   *
   * @param received сколько процентов уже проведено
   * @param expected сколько ещё ожидается по графику (включая не проведённые)
   * @param atMaturity сумма на вкладе к концу срока; {@code null} — бессрочный
   * @param effectiveRate ставка после налога с учётом капитализации, % годовых
   */
  public record DepositView(
      DepositRef deposit,
      BigDecimal balance,
      List<Row> rows,
      BigDecimal received,
      BigDecimal expected,
      @Nullable BigDecimal atMaturity,
      BigDecimal effectiveRate,
      @Nullable Long daysLeft) {

    public long dueCount() {
      return rows.stream().filter(r -> r.state() == State.DUE).count();
    }

    public @Nullable Row next() {
      return rows.stream().filter(r -> r.state() == State.FUTURE).findFirst().orElse(null);
    }
  }

  /** Начисление, которое пора провести. */
  public record Due(DepositRef deposit, Period period, AccountRef payTo) {}

  /**
   * @param months полученные проценты по месяцам, в базовой валюте
   * @param expected ещё ожидаются в этом году по графику
   * @param yield фактическая доходность за год, % годовых
   */
  public record YearRow(
      DepositRef deposit,
      BigDecimal[] months,
      BigDecimal received,
      BigDecimal expected,
      @Nullable BigDecimal yield) {}

  public record YearReport(
      int year,
      String currency,
      List<YearRow> rows,
      BigDecimal[] monthTotals,
      BigDecimal received,
      BigDecimal expected) {}
}
