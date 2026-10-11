package com.spendings.goal;

import com.spendings.account.AccountRef;
import com.spendings.account.Accounts;
import com.spendings.account.Accounts.AccountBalance;
import com.spendings.fx.Currencies;
import com.spendings.fx.ExchangeRates;
import com.spendings.goal.Goal.AccountShare;
import com.spendings.goal.Goal.Funding;
import com.spendings.goal.Goal.Kind;
import com.spendings.goal.Goal.Status;
import com.spendings.operation.OperationStats;
import com.spendings.shared.DomainException;
import com.spendings.shared.NotFoundException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Цели. Прогресс считается по реальным счетам (доля остатка в валюте цели по курсу на сегодня) или
 * по отметкам резерва; темп — средний взнос за последние полные месяцы.
 */
@Service
@Transactional(readOnly = true)
public class Goals {

  static final int HISTORY_MONTHS = 12;
  static final int PACE_MONTHS = 3;
  static final int EXPENSE_MONTHS = 12;

  private final GoalRepository goals;
  private final GoalContributionRepository contributions;
  private final Accounts accounts;
  private final ExchangeRates rates;
  private final Currencies currencies;
  private final OperationStats stats;

  Goals(
      GoalRepository goals,
      GoalContributionRepository contributions,
      Accounts accounts,
      ExchangeRates rates,
      Currencies currencies,
      OperationStats stats) {
    this.goals = goals;
    this.contributions = contributions;
    this.accounts = accounts;
    this.rates = rates;
    this.currencies = currencies;
    this.stats = stats;
  }

  /** Активные цели в порядке приоритета — для дашборда. */
  public List<GoalView> active() {
    return all().stream().filter(g -> g.status() == Status.ACTIVE).toList();
  }

  public List<GoalView> all() {
    YearMonth now = YearMonth.now();
    Map<UUID, BigDecimal> balances = balances();
    return goals.findAllByOrderByPriorityDescCreatedAtAsc().stream()
        .map(g -> view(g, now, balances))
        .toList();
  }

  public GoalView get(UUID id) {
    return view(load(id), YearMonth.now(), balances());
  }

  /** Отметки резерва, новые сверху. */
  List<Contribution> contributionsOf(UUID goalId) {
    return contributions.findByGoalIdOrderByMadeOnDescCreatedAtDesc(goalId).stream()
        .map(c -> new Contribution(c.id(), c.madeOn(), c.amount(), c.note()))
        .toList();
  }

  /** Что будет, если откладывать {@code monthly} в месяц. */
  @Nullable YearMonth whatIf(UUID id, BigDecimal monthly) {
    GoalView view = get(id);
    if (view.target() == null) {
      return null;
    }
    BigDecimal monthlyRate =
        view.annualRate().divide(BigDecimal.valueOf(1200), MathContext.DECIMAL64);
    if (view.current().compareTo(view.target()) >= 0) {
      return YearMonth.now();
    }
    return GoalForecast.eta(view.current(), view.target(), monthlyRate, monthly, YearMonth.now());
  }

  // ---- изменение: только для контроллера модуля ----

  GoalForm formFor(UUID id) {
    return GoalForm.of(load(id), accounts.active());
  }

  @Transactional
  UUID create(GoalForm form) {
    var goal = new Goal(form.getName().strip());
    apply(goal, form);
    return goals.saveAndFlush(goal).id();
  }

  @Transactional
  void update(UUID id, GoalForm form) {
    Goal goal = load(id);
    if (goal.version() != form.getVersion()) {
      throw new DomainException("error.concurrentEdit");
    }
    apply(goal, form);
  }

  @Transactional
  void achieve(UUID id) {
    load(id).achieve();
  }

  @Transactional
  void archive(UUID id) {
    load(id).archive();
  }

  @Transactional
  void restore(UUID id) {
    load(id).restore();
  }

  @Transactional
  void delete(UUID id) {
    goals.delete(load(id));
  }

  /** Отметка резерва: положительная — отложил, отрицательная — взял. */
  @Transactional
  void contribute(UUID goalId, LocalDate date, BigDecimal amount, @Nullable String note) {
    Goal goal = load(goalId);
    if (goal.funding() != Funding.MANUAL) {
      throw new DomainException("goal.error.notManual");
    }
    if (amount.signum() == 0) {
      throw new DomainException("operation.error.amountPositive");
    }
    if (amount.stripTrailingZeros().scale() > 2) {
      throw new DomainException("error.tooManyDecimals", 2);
    }
    contributions.save(
        new GoalContribution(
            goalId, date, amount, note == null || note.isBlank() ? null : note.strip()));
  }

  @Transactional
  void deleteContribution(UUID goalId, UUID contributionId) {
    contributions
        .findById(contributionId)
        .filter(c -> c.goalId().equals(goalId))
        .ifPresent(contributions::delete);
  }

  private void apply(Goal goal, GoalForm form) {
    if (!currencies.exists(form.getCurrency())) {
      throw new DomainException("account.error.unknownCurrency", form.getCurrency());
    }
    if (form.getKind() == Kind.AMOUNT
        && (form.getTargetAmount() == null || form.getTargetAmount().signum() <= 0)) {
      throw new DomainException("goal.error.targetRequired");
    }
    if (form.getKind() == Kind.MONTHS_OF_EXPENSES
        && (form.getTargetMonths() == null
            || form.getTargetMonths() < 1
            || form.getTargetMonths() > 120)) {
      throw new DomainException("goal.error.monthsRequired");
    }
    List<AccountShare> shares = new ArrayList<>();
    if (form.getFunding() == Funding.ACCOUNTS) {
      for (GoalForm.ShareRow row : form.getAccounts()) {
        if (!row.isSelected()) {
          continue;
        }
        BigDecimal share = row.getSharePct() == null ? BigDecimal.valueOf(100) : row.getSharePct();
        if (share.signum() <= 0 || share.compareTo(BigDecimal.valueOf(100)) > 0) {
          throw new DomainException("goal.error.shareRange");
        }
        AccountRef account = accounts.get(row.getAccountId());
        checkNotOverAllocated(account, share, goal);
        shares.add(new AccountShare(account.id(), share));
      }
      if (shares.isEmpty()) {
        throw new DomainException("goal.error.accountsRequired");
      }
    }
    goal.update(
        form.getName().strip(),
        form.getIcon() == null || form.getIcon().isBlank() ? null : form.getIcon().strip(),
        form.getKind(),
        form.getTargetAmount(),
        form.getTargetMonths() == null ? null : form.getTargetMonths().shortValue(),
        form.getCurrency(),
        form.getTargetDate(),
        form.getFunding(),
        form.getPriority(),
        shares);
  }

  /** Сумма долей одного счёта по всем активным целям не больше 100 %. */
  private void checkNotOverAllocated(AccountRef account, BigDecimal share, Goal self) {
    BigDecimal others =
        goals.findAll().stream()
            .filter(
                g -> g != self && g.status() == Status.ACTIVE && g.funding() == Funding.ACCOUNTS)
            .flatMap(g -> g.accounts().stream())
            .filter(s -> s.accountId().equals(account.id()))
            .map(AccountShare::sharePct)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    if (others.add(share).compareTo(BigDecimal.valueOf(100)) > 0) {
      throw new DomainException(
          "goal.error.overAllocated",
          account.name(),
          BigDecimal.valueOf(100).subtract(others).stripTrailingZeros().toPlainString());
    }
  }

  private GoalView view(Goal goal, YearMonth now, Map<UUID, BigDecimal> balances) {
    String currency = goal.currency();
    LocalDate today = LocalDate.now();
    boolean missingRates = false;

    // накоплено и ставка, по которой это растёт
    BigDecimal current = BigDecimal.ZERO;
    BigDecimal weightedRate = BigDecimal.ZERO;
    List<FundingAccount> funding = new ArrayList<>();
    if (goal.funding() == Funding.ACCOUNTS) {
      for (AccountShare share : goal.accounts()) {
        Optional<AccountRef> account = accounts.find(share.accountId());
        if (account.isEmpty()) {
          continue;
        }
        BigDecimal part =
            balances
                .getOrDefault(share.accountId(), BigDecimal.ZERO)
                .multiply(share.sharePct())
                .divide(BigDecimal.valueOf(100), MathContext.DECIMAL64);
        Optional<BigDecimal> converted =
            rates.convert(part, account.get().currency(), currency, today);
        if (converted.isEmpty()) {
          missingRates = true;
          continue;
        }
        BigDecimal rate = accounts.compoundingRate(share.accountId()).orElse(BigDecimal.ZERO);
        current = current.add(converted.get());
        weightedRate = weightedRate.add(converted.get().max(BigDecimal.ZERO).multiply(rate));
        funding.add(new FundingAccount(account.get(), share.sharePct(), converted.get()));
      }
    } else {
      current =
          contributions.findByGoalIdOrderByMadeOnDescCreatedAtDesc(goal.id()).stream()
              .map(GoalContribution::amount)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    BigDecimal annualRate =
        current.signum() > 0
            ? weightedRate.divide(current, 4, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;

    // цель
    BigDecimal target = goal.targetAmount();
    BigDecimal averageExpenses = null;
    if (goal.kind() == Kind.MONTHS_OF_EXPENSES && goal.targetMonths() != null) {
      averageExpenses = stats.averageMonthlyExpenses(currency, EXPENSE_MONTHS, now);
      target =
          averageExpenses.signum() > 0
              ? averageExpenses.multiply(BigDecimal.valueOf(goal.targetMonths()))
              : null;
    }

    // история взносов и темп
    Map<YearMonth, BigDecimal> history = history(goal, now);
    BigDecimal pace = pace(history, now);

    YearMonth deadline = goal.targetDate() == null ? null : YearMonth.from(goal.targetDate());
    GoalForecast forecast =
        target == null ? null : GoalForecast.of(current, target, annualRate, pace, now, deadline);
    BigDecimal percent = target == null ? BigDecimal.ZERO : GoalForecast.percent(current, target);

    return new GoalView(
        goal.id(),
        goal.name(),
        goal.icon() == null ? (goal.kind() == Kind.MONTHS_OF_EXPENSES ? "🛟" : "🎯") : goal.icon(),
        goal.kind(),
        goal.targetMonths() == null ? null : goal.targetMonths().intValue(),
        averageExpenses,
        currency,
        scale(current),
        target == null ? null : scale(target),
        percent,
        goal.targetDate(),
        goal.funding(),
        goal.status(),
        funding,
        annualRate,
        scale(pace),
        history,
        forecast,
        missingRates,
        goal.createdAt().atZone(ZoneId.systemDefault()).toLocalDate());
  }

  /** Взносы по месяцам за последние {@value #HISTORY_MONTHS} месяцев, включая текущий. */
  private Map<YearMonth, BigDecimal> history(Goal goal, YearMonth now) {
    YearMonth from = now.minusMonths(HISTORY_MONTHS - 1L);
    Map<YearMonth, BigDecimal> byMonth = new LinkedHashMap<>();
    for (YearMonth m = from; !m.isAfter(now); m = m.plusMonths(1)) {
      byMonth.put(m, BigDecimal.ZERO);
    }
    if (goal.funding() == Funding.ACCOUNTS) {
      Map<UUID, BigDecimal> shares =
          goal.accounts().stream()
              .collect(Collectors.toMap(AccountShare::accountId, AccountShare::sharePct));
      Map<UUID, Map<YearMonth, BigDecimal>> net =
          stats.monthlyNet(List.copyOf(shares.keySet()), goal.currency(), from);
      net.forEach(
          (accountId, months) ->
              months.forEach(
                  (month, amount) ->
                      byMonth.computeIfPresent(
                          month,
                          (m, sum) ->
                              sum.add(
                                  amount
                                      .multiply(shares.get(accountId))
                                      .divide(BigDecimal.valueOf(100), MathContext.DECIMAL64)))));
    } else {
      for (GoalContribution c :
          contributions.findByGoalIdOrderByMadeOnDescCreatedAtDesc(goal.id())) {
        byMonth.computeIfPresent(YearMonth.from(c.madeOn()), (m, sum) -> sum.add(c.amount()));
      }
    }
    byMonth.replaceAll((m, v) -> scale(v));
    return byMonth;
  }

  /**
   * Средний взнос за последние {@value #PACE_MONTHS} полных месяца, считая только месяцы после
   * первого взноса. Если полных месяцев ещё нет — взнос текущего месяца.
   */
  static BigDecimal pace(Map<YearMonth, BigDecimal> history, YearMonth now) {
    YearMonth first =
        history.entrySet().stream()
            .filter(e -> e.getValue().signum() != 0)
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(null);
    if (first == null) {
      return BigDecimal.ZERO;
    }
    BigDecimal sum = BigDecimal.ZERO;
    int months = 0;
    for (YearMonth m = now.minusMonths(PACE_MONTHS); m.isBefore(now); m = m.plusMonths(1)) {
      if (!m.isBefore(first)) {
        sum = sum.add(history.getOrDefault(m, BigDecimal.ZERO));
        months++;
      }
    }
    if (months == 0) {
      return history.getOrDefault(now, BigDecimal.ZERO);
    }
    return sum.divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP);
  }

  private Map<UUID, BigDecimal> balances() {
    Map<UUID, BigDecimal> balances = new HashMap<>();
    for (AccountBalance b : accounts.balances()) {
      balances.put(b.account().id(), b.balance());
    }
    return balances;
  }

  private Goal load(UUID id) {
    return goals.findById(id).orElseThrow(() -> new NotFoundException("Цель"));
  }

  private static BigDecimal scale(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_EVEN);
  }

  /**
   * Цель для показа.
   *
   * @param target сумма цели; {@code null} для «N месяцев расходов», пока нет истории расходов
   * @param annualRate средняя ставка накоплений, % годовых
   * @param pace средний взнос в месяц
   * @param forecast {@code null}, если сумма цели неизвестна
   */
  public record GoalView(
      UUID id,
      String name,
      String icon,
      Kind kind,
      @Nullable Integer targetMonths,
      @Nullable BigDecimal averageExpenses,
      String currency,
      BigDecimal current,
      @Nullable BigDecimal target,
      BigDecimal percent,
      @Nullable LocalDate targetDate,
      Funding funding,
      Status status,
      List<FundingAccount> accounts,
      BigDecimal annualRate,
      BigDecimal pace,
      Map<YearMonth, BigDecimal> history,
      @Nullable GoalForecast forecast,
      boolean missingRates,
      LocalDate createdOn) {

    public boolean isActive() {
      return status == Status.ACTIVE;
    }

    public boolean canBeMarkedAchieved() {
      return status == Status.ACTIVE
          && forecast != null
          && forecast.status() == GoalForecast.Status.ACHIEVED;
    }

    /** Ключ подписи статуса в messages.properties. */
    public String statusKey() {
      if (status != Status.ACTIVE) {
        return "goal.status." + status;
      }
      return forecast == null ? "goal.forecast.noTarget" : "goal.forecast." + forecast.status();
    }

    /** Число месяцев для подписи «отстаю на N мес.» или «опережаю на N мес.». */
    public long statusMonths() {
      return forecast == null || forecast.monthsLate() == null
          ? 0
          : Math.abs(forecast.monthsLate());
    }

    /** Цвет плашки статуса. */
    public String statusClass() {
      if (status != Status.ACTIVE) {
        return "text-bg-secondary";
      }
      if (forecast == null) {
        return "text-bg-light";
      }
      return switch (forecast.status()) {
        case ACHIEVED, AHEAD, ON_TRACK -> "text-bg-success";
        case BEHIND -> "text-bg-warning";
        case NO_PACE -> "text-bg-danger";
        case NO_DEADLINE -> "text-bg-info";
      };
    }

    /** Самый большой по модулю взнос за месяц — для высоты столбиков. */
    public BigDecimal historyMax() {
      return history.values().stream()
          .map(BigDecimal::abs)
          .max(BigDecimal::compareTo)
          .orElse(BigDecimal.ZERO);
    }

    /** Высота столбика месяца в процентах от самого большого. */
    public long barHeight(BigDecimal value) {
      BigDecimal max = historyMax();
      return max.signum() == 0
          ? 0
          : value
              .abs()
              .multiply(BigDecimal.valueOf(100))
              .divide(max, 0, RoundingMode.HALF_UP)
              .longValue();
    }
  }

  /** Счёт цели и сколько из его остатка относится к ней, в валюте цели. */
  public record FundingAccount(AccountRef account, BigDecimal sharePct, BigDecimal amount) {

    /** «» для всего остатка или « · 50%» для доли. */
    public String shareLabel() {
      return sharePct.compareTo(BigDecimal.valueOf(100)) == 0
          ? ""
          : " · " + sharePct.stripTrailingZeros().toPlainString() + "%";
    }
  }

  public record Contribution(UUID id, LocalDate date, BigDecimal amount, @Nullable String note) {}
}
