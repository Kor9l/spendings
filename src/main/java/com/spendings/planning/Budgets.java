package com.spendings.planning;

import com.spendings.category.Categories;
import com.spendings.category.CategoryRef;
import com.spendings.fx.BaseCurrency;
import com.spendings.fx.ExchangeRates;
import com.spendings.operation.OperationType;
import com.spendings.planning.Planner.Due;
import com.spendings.planning.Schedule.BudgetPeriod;
import com.spendings.shared.DomainException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Бюджеты на бюджетный месяц, в базовой валюте. Бюджет категории верхнего уровня включает её
 * подкатегории. С переносом остатка неизрасходованное (или перерасход) переходит в следующий месяц.
 */
@Service
@Transactional(readOnly = true)
public class Budgets {

  static final BigDecimal WARNING = BigDecimal.valueOf(80);
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  /** Перенос остатка считаем не дальше двух лет назад. */
  private static final int MAX_CARRY_MONTHS = 24;

  private final JdbcClient jdbc;
  private final Categories categories;
  private final Planner planner;
  private final ExchangeRates rates;
  private final BaseCurrency baseCurrency;

  Budgets(
      JdbcClient jdbc,
      Categories categories,
      Planner planner,
      ExchangeRates rates,
      BaseCurrency baseCurrency) {
    this.jdbc = jdbc;
    this.categories = categories;
    this.planner = planner;
    this.rates = rates;
    this.baseCurrency = baseCurrency;
  }

  public int startDay() {
    return jdbc.sql("select budget_month_start from app_settings").query(Integer.class).single();
  }

  @Transactional
  void changeStartDay(int day) {
    if (day < 1 || day > 28) {
      throw new DomainException("planning.error.startDay");
    }
    jdbc.sql("update app_settings set budget_month_start = ?").param(day).update();
  }

  public BudgetPeriod current() {
    return BudgetPeriod.containing(LocalDate.now(), startDay());
  }

  /** Бюджеты месяца с фактом, плановыми расходами и переносом остатка. */
  public BudgetView view(YearMonth month) {
    BudgetPeriod period = BudgetPeriod.of(month, startDay());
    String base = baseCurrency.code();
    Map<UUID, CategoryRef> all = categories.all();
    Map<UUID, BigDecimal> spentByLeaf = spentByCategory(period, base);
    Map<UUID, BigDecimal> plannedByLeaf = plannedByCategory(period, base);

    List<BudgetRow> rows = new ArrayList<>();
    Set<UUID> covered = new HashSet<>();
    // лимит подкатегории уже входит в лимит родителя, если у родителя есть свой бюджет
    BigDecimal limits = BigDecimal.ZERO;
    for (Stored b : stored(month)) {
      CategoryRef category = all.get(b.categoryId());
      if (category == null) {
        continue;
      }
      Set<UUID> subtree = subtree(category, all);
      BigDecimal spent = sum(spentByLeaf, subtree);
      BigDecimal planned = sum(plannedByLeaf, subtree);
      BigDecimal carry = carry(b.categoryId(), month.minusMonths(1), 0, all);
      BudgetRow row = row(b, category, carry, spent, planned);
      if (category.parentId() == null || !budgetedParent(category, month)) {
        covered.addAll(subtree);
        limits = limits.add(row.available());
      }
      rows.add(row);
    }
    rows.sort((a, c) -> a.category().displayName().compareToIgnoreCase(c.category().displayName()));

    BigDecimal totalSpent = spentByLeaf.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal unbudgeted = BigDecimal.ZERO;
    for (var e : spentByLeaf.entrySet()) {
      if (!covered.contains(e.getKey())) {
        unbudgeted = unbudgeted.add(e.getValue());
      }
    }
    return new BudgetView(period, base, rows, scale(totalSpent), scale(unbudgeted), scale(limits));
  }

  /** Бюджеты текущего месяца, израсходованные на 80 % и больше. */
  public List<BudgetRow> warnings() {
    return view(current().month()).rows().stream()
        .filter(r -> r.percent().compareTo(WARNING) >= 0)
        .sorted((a, b) -> b.percent().compareTo(a.percent()))
        .toList();
  }

  @Transactional
  void save(UUID categoryId, YearMonth month, BigDecimal amount, boolean rollover) {
    if (amount.signum() < 0) {
      throw new DomainException("operation.error.amountPositive");
    }
    categories.get(categoryId);
    jdbc.sql(
            """
            insert into budget (category_id, month, amount, rollover) values (?, ?, ?, ?)
            on conflict (category_id, month) do update set amount = excluded.amount, rollover = excluded.rollover
            """)
        .params(categoryId, month.atDay(1), amount, rollover)
        .update();
  }

  @Transactional
  void delete(UUID id) {
    jdbc.sql("delete from budget where id = ?").param(id).update();
  }

  /** Копирует бюджеты прошлого месяца в этот — только тех категорий, у которых ещё нет бюджета. */
  @Transactional
  int copyFromPrevious(YearMonth month) {
    return jdbc.sql(
            """
            insert into budget (category_id, month, amount, rollover)
            select category_id, :month, amount, rollover from budget where month = :previous
            on conflict (category_id, month) do nothing
            """)
        .param("month", month.atDay(1))
        .param("previous", month.minusMonths(1).atDay(1))
        .update();
  }

  // ---- внутреннее ----

  private BudgetRow row(
      Stored b, CategoryRef category, BigDecimal carry, BigDecimal spent, BigDecimal planned) {
    BigDecimal available = b.amount().add(carry);
    BigDecimal percent =
        available.signum() > 0
            ? spent.multiply(HUNDRED).divide(available, 1, RoundingMode.HALF_UP)
            : spent.signum() > 0 ? BigDecimal.valueOf(999) : BigDecimal.ZERO;
    return new BudgetRow(
        b.id(),
        category,
        scale(b.amount()),
        scale(carry),
        scale(available),
        scale(spent),
        scale(planned),
        scale(available.subtract(spent)),
        scale(spent.add(planned)),
        percent,
        b.rollover());
  }

  /** Остаток, перенесённый из {@code month} в следующий месяц. */
  private BigDecimal carry(
      UUID categoryId, YearMonth month, int depth, Map<UUID, CategoryRef> all) {
    if (depth >= MAX_CARRY_MONTHS) {
      return BigDecimal.ZERO;
    }
    Stored previous =
        stored(month).stream()
            .filter(s -> s.categoryId().equals(categoryId))
            .findFirst()
            .orElse(null);
    if (previous == null || !previous.rollover()) {
      return BigDecimal.ZERO;
    }
    BudgetPeriod period = BudgetPeriod.of(month, startDay());
    CategoryRef category = all.get(categoryId);
    if (category == null) {
      return BigDecimal.ZERO;
    }
    BigDecimal spent = sum(spentByCategory(period, baseCurrency.code()), subtree(category, all));
    return previous
        .amount()
        .add(carry(categoryId, month.minusMonths(1), depth + 1, all))
        .subtract(spent);
  }

  private boolean budgetedParent(CategoryRef category, YearMonth month) {
    return category.parentId() != null
        && stored(month).stream().anyMatch(s -> s.categoryId().equals(category.parentId()));
  }

  private List<Stored> stored(YearMonth month) {
    return jdbc.sql("select id, category_id, amount, rollover from budget where month = ?")
        .param(month.atDay(1))
        .query(
            (rs, i) ->
                new Stored(
                    rs.getObject("id", UUID.class),
                    rs.getObject("category_id", UUID.class),
                    rs.getBigDecimal("amount"),
                    rs.getBoolean("rollover")))
        .list();
  }

  /** Расходы периода по конечной категории, в базовой валюте, положительными числами. */
  private Map<UUID, BigDecimal> spentByCategory(BudgetPeriod period, String base) {
    Map<UUID, BigDecimal> result = new HashMap<>();
    jdbc.sql(
            """
            select l.category_id, -sum(l.amount * fx_rate(a.currency, :base, o.occurred_on)) as spent
            from operation o
            join operation_line l on l.operation_id = o.id
            join account a on a.id = l.account_id
            where o.status = 'CLEARED' and o.type = 'EXPENSE' and o.occurred_on between :from and :to
            group by l.category_id
            """)
        .param("base", base)
        .param("from", period.from())
        .param("to", period.to())
        .query(
            (rs, i) -> {
              BigDecimal spent = rs.getBigDecimal("spent");
              if (spent != null) {
                result.put(rs.getObject("category_id", UUID.class), spent);
              }
              return null;
            })
        .list();
    return result;
  }

  /** Ещё не проведённые регулярные расходы периода (и просроченные), по категориям. */
  private Map<UUID, BigDecimal> plannedByCategory(BudgetPeriod period, String base) {
    Map<UUID, BigDecimal> result = new HashMap<>();
    LocalDate today = LocalDate.now();
    if (period.to().isBefore(today.minusDays(Planner.OVERDUE_DAYS))) {
      return result;
    }
    for (Due due : planner.due(period.from(), period.to())) {
      if (due.type() != OperationType.EXPENSE || due.categoryId() == null) {
        continue;
      }
      rates
          .convert(due.amount(), due.currency(), base, today)
          .ifPresent(amount -> result.merge(due.categoryId(), amount, BigDecimal::add));
    }
    return result;
  }

  private static Set<UUID> subtree(CategoryRef category, Map<UUID, CategoryRef> all) {
    Set<UUID> ids = new HashSet<>();
    ids.add(category.id());
    if (category.parentId() == null) {
      for (CategoryRef c : all.values()) {
        if (category.id().equals(c.parentId())) {
          ids.add(c.id());
        }
      }
    }
    return ids;
  }

  private static BigDecimal sum(Map<UUID, BigDecimal> byCategory, Set<UUID> ids) {
    return ids.stream()
        .map(id -> byCategory.getOrDefault(id, BigDecimal.ZERO))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private static BigDecimal scale(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_EVEN);
  }

  private record Stored(UUID id, UUID categoryId, BigDecimal amount, boolean rollover) {}

  /**
   * Бюджет категории за месяц.
   *
   * @param carry перенос из прошлого месяца (отрицательный — перерасход)
   * @param available лимит + перенос
   * @param planned ещё не проведённые регулярные расходы этого месяца
   * @param forecast сколько будет потрачено с учётом плановых
   * @param percent израсходовано от доступного, %
   */
  public record BudgetRow(
      UUID id,
      CategoryRef category,
      BigDecimal limit,
      BigDecimal carry,
      BigDecimal available,
      BigDecimal spent,
      BigDecimal planned,
      BigDecimal remaining,
      BigDecimal forecast,
      BigDecimal percent,
      boolean rollover) {

    public String level() {
      if (percent.compareTo(HUNDRED) >= 0) {
        return "danger";
      }
      return percent.compareTo(WARNING) >= 0 ? "warning" : "success";
    }

    public boolean forecastOver() {
      return forecast.compareTo(available) > 0;
    }

    /** Ширина полосы «потрачено» в процентах, 0…100. */
    public BigDecimal bar() {
      return percent.min(HUNDRED);
    }
  }

  /**
   * @param unbudgeted расходы в категориях без бюджета
   * @param totalLimit сумма лимитов с переносами
   */
  public record BudgetView(
      BudgetPeriod period,
      String currency,
      List<BudgetRow> rows,
      BigDecimal totalSpent,
      BigDecimal unbudgeted,
      @Nullable BigDecimal totalLimit) {}
}
