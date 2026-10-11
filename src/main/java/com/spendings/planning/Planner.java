package com.spendings.planning;

import com.spendings.account.AccountRef;
import com.spendings.account.Accounts;
import com.spendings.account.Accounts.AccountBalance;
import com.spendings.category.Categories;
import com.spendings.category.CategoryRef;
import com.spendings.fx.BaseCurrency;
import com.spendings.fx.ExchangeRates;
import com.spendings.operation.OperationRecorded;
import com.spendings.operation.OperationType;
import com.spendings.operation.Operations;
import com.spendings.operation.RecordOperation;
import com.spendings.operation.RecordOperation.RecordExpense;
import com.spendings.operation.RecordOperation.RecordIncome;
import com.spendings.operation.RecordOperation.RecordTransfer;
import com.spendings.operation.RecordOperation.Split;
import com.spendings.shared.DomainException;
import com.spendings.shared.NotFoundException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Регулярные операции. Ожидаемые даты вычисляются из расписания; в базе хранятся только решённые
 * исполнения — проведённые (со ссылкой на операцию) и пропущенные.
 */
@Service
@Transactional(readOnly = true)
public class Planner {

  /** Невыполненные платежи старше месяца уже не показываются как просроченные. */
  static final int OVERDUE_DAYS = 31;

  /** Операция закрывает платёж, если её дата в окне [срок − 2; срок + 7] дней. */
  static final int MATCH_DAYS_BEFORE = 2;

  static final int MATCH_DAYS_AFTER = 7;

  private static final ThreadLocal<Boolean> POSTING = ThreadLocal.withInitial(() -> false);

  private final RuleRepository rules;
  private final JdbcClient jdbc;
  private final Operations operations;
  private final Accounts accounts;
  private final Categories categories;
  private final ExchangeRates rates;
  private final BaseCurrency baseCurrency;

  Planner(
      RuleRepository rules,
      JdbcClient jdbc,
      Operations operations,
      Accounts accounts,
      Categories categories,
      ExchangeRates rates,
      BaseCurrency baseCurrency) {
    this.rules = rules;
    this.jdbc = jdbc;
    this.operations = operations;
    this.accounts = accounts;
    this.categories = categories;
    this.rates = rates;
    this.baseCurrency = baseCurrency;
  }

  // ---- чтение ----

  /** Ещё не решённые платежи активных правил в интервале, по дате. */
  public List<Due> due(LocalDate from, LocalDate to) {
    LocalDate today = LocalDate.now();
    return occurrences(from, to).stream()
        .filter(o -> o.status() == null)
        .map(o -> o.due().withOverdue(o.due().date().isBefore(today)))
        .toList();
  }

  /** Просроченные (не старше месяца) и ближайшие {@code days} дней. */
  public List<Due> upcoming(int days) {
    LocalDate today = LocalDate.now();
    return due(today.minusDays(OVERDUE_DAYS), today.plusDays(days));
  }

  /** Все исполнения месяца — ожидаемые, проведённые и пропущенные — по дням. */
  Map<LocalDate, List<Occurrence>> calendar(YearMonth month) {
    return occurrences(month.atDay(1), month.atEndOfMonth()).stream()
        .collect(
            Collectors.groupingBy(
                o -> o.due().date(), java.util.TreeMap::new, Collectors.toList()));
  }

  /**
   * Прогноз: остатки всех счетов в базовой валюте сейчас плюс ожидаемые доходы и минус ожидаемые
   * расходы до {@code until}, включая просроченные. Переводы между своими счетами итог не меняют.
   */
  public Forecast forecast(LocalDate until) {
    String base = baseCurrency.code();
    LocalDate today = LocalDate.now();
    BigDecimal now = BigDecimal.ZERO;
    boolean missing = false;
    for (AccountBalance b : accounts.balances()) {
      if (b.account().archived()) {
        continue;
      }
      Optional<BigDecimal> converted =
          rates.convert(b.balance(), b.account().currency(), base, today);
      if (converted.isPresent()) {
        now = now.add(converted.get());
      } else {
        missing = true;
      }
    }
    BigDecimal income = BigDecimal.ZERO;
    BigDecimal expenses = BigDecimal.ZERO;
    for (Due due : due(today.minusDays(OVERDUE_DAYS), until)) {
      if (due.type() == OperationType.TRANSFER) {
        continue;
      }
      Optional<BigDecimal> converted = rates.convert(due.amount(), due.currency(), base, today);
      if (converted.isEmpty()) {
        missing = true;
        continue;
      }
      if (due.type() == OperationType.INCOME) {
        income = income.add(converted.get());
      } else {
        expenses = expenses.add(converted.get());
      }
    }
    return new Forecast(
        base,
        until,
        scale(now),
        scale(income),
        scale(expenses),
        scale(now.add(income).subtract(expenses)),
        missing);
  }

  List<RuleView> rules() {
    Map<UUID, AccountRef> accountsById = accountsById();
    Map<UUID, CategoryRef> categoriesById = categories.all();
    return rules.findAll(org.springframework.data.domain.Sort.by("name")).stream()
        .sorted(Comparator.comparing(RecurringRule::active).reversed())
        .map(r -> view(r, accountsById, categoriesById))
        .toList();
  }

  RuleForm formFor(UUID id) {
    return RuleForm.of(load(id));
  }

  // ---- изменение ----

  @Transactional
  UUID create(RuleForm form) {
    RecurringRule rule = RecurringRule.create();
    validate(form);
    rule.update(form);
    return rules.saveAndFlush(rule).id();
  }

  @Transactional
  void update(UUID id, RuleForm form) {
    RecurringRule rule = load(id);
    if (rule.version() != form.getVersion()) {
      throw new DomainException("error.concurrentEdit");
    }
    validate(form);
    rule.update(form);
  }

  @Transactional
  void toggle(UUID id) {
    RecurringRule rule = load(id);
    if (rule.active()) {
      rule.deactivate();
    } else {
      rule.activate();
    }
  }

  @Transactional
  void delete(UUID id) {
    rules.delete(load(id));
  }

  /** Проводит ожидаемый платёж: создаёт операцию и отмечает исполнение проведённым. */
  @Transactional
  public UUID post(
      UUID ruleId,
      LocalDate dueOn,
      BigDecimal amount,
      @Nullable BigDecimal toAmount,
      LocalDate date) {
    RecurringRule rule = load(ruleId);
    RecordOperation command =
        switch (rule.type()) {
          case EXPENSE ->
              new RecordExpense(
                  rule.accountId(),
                  date,
                  List.of(new Split(requireCategory(rule), amount)),
                  rule.payee(),
                  rule.name());
          case INCOME ->
              new RecordIncome(
                  rule.accountId(),
                  date,
                  List.of(new Split(requireCategory(rule), amount)),
                  rule.payee(),
                  rule.name());
          case TRANSFER ->
              new RecordTransfer(
                  rule.accountId(),
                  amount,
                  java.util.Objects.requireNonNull(rule.toAccountId()),
                  toAmount != null ? toAmount : rule.toAmount() != null ? rule.toAmount() : amount,
                  date,
                  rule.name());
          case ADJUSTMENT -> throw new IllegalStateException("Корректировка не бывает регулярной");
        };
    UUID operationId;
    POSTING.set(true);
    try {
      operationId = operations.record(command);
    } finally {
      POSTING.set(false);
    }
    resolve(ruleId, dueOn, "POSTED", operationId);
    return operationId;
  }

  @Transactional
  public void skip(UUID ruleId, LocalDate dueOn) {
    load(ruleId);
    resolve(ruleId, dueOn, "SKIPPED", null);
  }

  /** Проводит наступившие платежи правил с автопроведением; возвращает, сколько проведено. */
  @Transactional
  public int autoPost(LocalDate today) {
    int posted = 0;
    for (Due due : due(today.minusDays(OVERDUE_DAYS), today)) {
      if (due.autoPost()) {
        post(due.ruleId(), due.date(), due.amount(), null, due.date());
        posted++;
      }
    }
    return posted;
  }

  /**
   * Новая операция закрывает ожидаемый платёж, если совпадают тип и счёт, сумма отличается не
   * больше допуска правила, а дата — в окне вокруг срока. Из подходящих берётся ближайший по дате.
   */
  @EventListener
  @Transactional
  void on(OperationRecorded recorded) {
    if (POSTING.get() || recorded.type() == OperationType.ADJUSTMENT) {
      return;
    }
    BigDecimal amount = recorded.amount().abs();
    LocalDate from = recorded.date().minusDays(MATCH_DAYS_AFTER);
    LocalDate to = recorded.date().plusDays(MATCH_DAYS_BEFORE);
    Map<String, String> resolved = resolved(from, to);
    Occurrence best = null;
    long bestDistance = Long.MAX_VALUE;
    for (RecurringRule rule : rules.findAll()) {
      if (!rule.active()
          || rule.type() != recorded.type()
          || !rule.accountId().equals(recorded.accountId())) {
        continue;
      }
      BigDecimal allowed =
          rule.amount()
              .multiply(rule.tolerancePct())
              .divide(BigDecimal.valueOf(100), MathContext.DECIMAL64);
      if (amount.subtract(rule.amount()).abs().compareTo(allowed) > 0) {
        continue;
      }
      for (LocalDate date : rule.schedule().between(from, to)) {
        long distance = Math.abs(date.toEpochDay() - recorded.date().toEpochDay());
        if (!resolved.containsKey(key(rule.id(), date)) && distance < bestDistance) {
          best = new Occurrence(dueOf(rule, date, Map.of(), Map.of()), null, null);
          bestDistance = distance;
        }
      }
    }
    if (best != null) {
      jdbc.sql(
              """
              insert into planned_occurrence (rule_id, due_on, status, operation_id)
              values (?, ?, 'POSTED', ?) on conflict do nothing
              """)
          .params(best.due().ruleId(), best.due().date(), recorded.operationId())
          .update();
    }
  }

  // ---- внутреннее ----

  private List<Occurrence> occurrences(LocalDate from, LocalDate to) {
    Map<UUID, AccountRef> accountsById = accountsById();
    Map<UUID, CategoryRef> categoriesById = categories.all();
    Map<String, String> resolved = resolved(from, to);
    Map<String, UUID> operationsByKey = resolvedOperations(from, to);
    List<Occurrence> result = new ArrayList<>();
    for (RecurringRule rule : rules.findAll()) {
      if (!rule.active()) {
        continue;
      }
      for (LocalDate date : rule.schedule().between(from, to)) {
        String key = key(rule.id(), date);
        result.add(
            new Occurrence(
                dueOf(rule, date, accountsById, categoriesById),
                resolved.get(key),
                operationsByKey.get(key)));
      }
    }
    result.sort(
        Comparator.comparing((Occurrence o) -> o.due().date()).thenComparing(o -> o.due().name()));
    return result;
  }

  private Due dueOf(
      RecurringRule rule,
      LocalDate date,
      Map<UUID, AccountRef> accountsById,
      Map<UUID, CategoryRef> categoriesById) {
    AccountRef account = accountsById.get(rule.accountId());
    CategoryRef category = rule.categoryId() == null ? null : categoriesById.get(rule.categoryId());
    return new Due(
        rule.id(),
        rule.name(),
        rule.type(),
        date,
        rule.amount(),
        account == null ? "" : account.currency(),
        account == null ? "" : account.name(),
        rule.categoryId(),
        category == null ? (rule.type() == OperationType.TRANSFER ? "🔁" : "🗓️") : category.icon(),
        category == null ? "#5c7cfa" : category.color(),
        rule.autoPost(),
        false);
  }

  private Map<String, String> resolved(LocalDate from, LocalDate to) {
    Map<String, String> result = new HashMap<>();
    jdbc.sql("select rule_id, due_on, status from planned_occurrence where due_on between ? and ?")
        .params(from, to)
        .query(
            (rs, i) ->
                result.put(
                    key(
                        rs.getObject("rule_id", UUID.class),
                        rs.getObject("due_on", LocalDate.class)),
                    rs.getString("status")))
        .list();
    return result;
  }

  private Map<String, UUID> resolvedOperations(LocalDate from, LocalDate to) {
    Map<String, UUID> result = new HashMap<>();
    jdbc.sql(
            "select rule_id, due_on, operation_id from planned_occurrence where due_on between ? and ? and operation_id is not null")
        .params(from, to)
        .query(
            (rs, i) ->
                result.put(
                    key(
                        rs.getObject("rule_id", UUID.class),
                        rs.getObject("due_on", LocalDate.class)),
                    rs.getObject("operation_id", UUID.class)))
        .list();
    return result;
  }

  private void resolve(UUID ruleId, LocalDate dueOn, String status, @Nullable UUID operationId) {
    jdbc.sql(
            """
            insert into planned_occurrence (rule_id, due_on, status, operation_id) values (?, ?, ?, ?)
            on conflict (rule_id, due_on)
            do update set status = excluded.status, operation_id = excluded.operation_id, resolved_at = now()
            """)
        .params(ruleId, dueOn, status, operationId)
        .update();
  }

  private void validate(RuleForm form) {
    AccountRef account =
        accounts
            .find(form.requireAccountId())
            .orElseThrow(() -> new DomainException("operation.error.accountRequired"));
    form.requireAmount();
    if (form.getType() == OperationType.ADJUSTMENT) {
      throw new DomainException("planning.error.type");
    }
    if (form.getType() == OperationType.TRANSFER) {
      if (form.getToAccountId() == null || form.getToAccountId().equals(account.id())) {
        throw new DomainException("operation.error.toAccountRequired");
      }
    } else {
      if (form.getCategoryId() == null) {
        throw new DomainException("operation.error.categoryRequired");
      }
      CategoryRef category = categories.get(form.getCategoryId());
      if (!category.kind().name().equals(form.getType().name())) {
        throw new DomainException("operation.error.categoryKindMismatch", category.displayName());
      }
    }
    if (form.getFrequency() == Schedule.Frequency.MONTHLY
        && (form.getDayOfMonth() == null
            || form.getDayOfMonth() < 1
            || form.getDayOfMonth() > 31)) {
      throw new DomainException("planning.error.dayOfMonth");
    }
  }

  private static UUID requireCategory(RecurringRule rule) {
    if (rule.categoryId() == null) {
      throw new DomainException("operation.error.categoryRequired");
    }
    return rule.categoryId();
  }

  private RecurringRule load(UUID id) {
    return rules.findById(id).orElseThrow(() -> new NotFoundException("Регулярная операция"));
  }

  private Map<UUID, AccountRef> accountsById() {
    return accounts.all().stream().collect(Collectors.toMap(AccountRef::id, Function.identity()));
  }

  private RuleView view(
      RecurringRule r, Map<UUID, AccountRef> accountsById, Map<UUID, CategoryRef> categoriesById) {
    AccountRef account = accountsById.get(r.accountId());
    AccountRef to = r.toAccountId() == null ? null : accountsById.get(r.toAccountId());
    CategoryRef category = r.categoryId() == null ? null : categoriesById.get(r.categoryId());
    LocalDate today = LocalDate.now();
    LocalDate next =
        r.schedule().between(today, today.plusYears(2)).stream().findFirst().orElse(null);
    return new RuleView(
        r.id(),
        r.name(),
        r.type(),
        r.amount(),
        account == null ? "" : account.currency(),
        account == null ? "?" : account.name(),
        to == null ? null : to.name(),
        category == null ? null : category.displayName(),
        r.frequency(),
        r.interval(),
        r.dayOfMonth(),
        r.dayOfWeek(),
        r.autoPost(),
        r.active(),
        next);
  }

  private static String key(UUID ruleId, LocalDate date) {
    return ruleId + "|" + date;
  }

  private static BigDecimal scale(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_EVEN);
  }

  /** Ожидаемый платёж. Сумма — в валюте счёта правила. */
  public record Due(
      UUID ruleId,
      String name,
      OperationType type,
      LocalDate date,
      BigDecimal amount,
      String currency,
      String accountName,
      @Nullable UUID categoryId,
      String icon,
      String color,
      boolean autoPost,
      boolean overdue) {

    Due withOverdue(boolean overdue) {
      return new Due(
          ruleId,
          name,
          type,
          date,
          amount,
          currency,
          accountName,
          categoryId,
          icon,
          color,
          autoPost,
          overdue);
    }

    /** Сумма со знаком для показа: расход — с минусом. */
    public BigDecimal signedAmount() {
      return type == OperationType.EXPENSE ? amount.negate() : amount;
    }
  }

  /**
   * @param status {@code null} — ещё ожидается, иначе POSTED или SKIPPED
   */
  record Occurrence(Due due, @Nullable String status, @Nullable UUID operationId) {

    public boolean posted() {
      return "POSTED".equals(status);
    }

    public boolean skipped() {
      return "SKIPPED".equals(status);
    }
  }

  public record Forecast(
      String currency,
      LocalDate until,
      BigDecimal now,
      BigDecimal plannedIncome,
      BigDecimal plannedExpenses,
      BigDecimal end,
      boolean missingRates) {}

  record RuleView(
      UUID id,
      String name,
      OperationType type,
      BigDecimal amount,
      String currency,
      String account,
      @Nullable String toAccount,
      @Nullable String category,
      Schedule.Frequency frequency,
      int interval,
      @Nullable Integer dayOfMonth,
      @Nullable Integer dayOfWeek,
      boolean autoPost,
      boolean active,
      @Nullable LocalDate next) {}
}
