package com.spendings.goal;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.YearMonth;
import org.jspecify.annotations.Nullable;

/**
 * Прогноз цели. Деньги растут раз в месяц: сначала проценты (если деньги на вкладе с
 * капитализацией), потом очередной взнос. Чистая функция — без базы и Spring.
 *
 * @param requiredMonthly сколько откладывать в месяц, чтобы успеть к сроку; {@code null} без срока
 * @param eta месяц достижения при текущем темпе; {@code null}, если при таком темпе не достичь
 * @param monthsLate на сколько месяцев позже срока (отрицательное — раньше); {@code null}, если
 *     срока или прогноза нет
 */
public record GoalForecast(
    Status status,
    BigDecimal remaining,
    @Nullable BigDecimal requiredMonthly,
    @Nullable YearMonth eta,
    @Nullable Long monthsLate) {

  /** Дальше 50 лет прогноз бессмыслен. */
  static final int MAX_MONTHS = 600;

  private static final MathContext MC = MathContext.DECIMAL64;
  private static final BigDecimal TWELVE_HUNDRED = BigDecimal.valueOf(1200);

  public enum Status {
    ACHIEVED,
    AHEAD,
    ON_TRACK,
    BEHIND,
    NO_PACE,
    NO_DEADLINE
  }

  /**
   * @param current сколько уже накоплено
   * @param target сколько нужно
   * @param annualRatePct ставка в % годовых, по которой растут накопленные деньги (0 — не растут)
   * @param monthlyPace средний взнос в месяц
   * @param now текущий месяц
   * @param deadline месяц срока или {@code null}
   */
  public static GoalForecast of(
      BigDecimal current,
      BigDecimal target,
      BigDecimal annualRatePct,
      BigDecimal monthlyPace,
      YearMonth now,
      @Nullable YearMonth deadline) {
    BigDecimal remaining = target.subtract(current).max(BigDecimal.ZERO);
    if (remaining.signum() == 0) {
      return new GoalForecast(Status.ACHIEVED, BigDecimal.ZERO, null, now, null);
    }
    BigDecimal rate = annualRatePct.divide(TWELVE_HUNDRED, MC);

    YearMonth eta = eta(current, target, rate, monthlyPace, now);
    BigDecimal required = deadline == null ? null : required(current, target, rate, now, deadline);

    if (deadline == null) {
      return new GoalForecast(
          eta == null ? Status.NO_PACE : Status.NO_DEADLINE, remaining, null, eta, null);
    }
    if (eta == null) {
      return new GoalForecast(Status.NO_PACE, remaining, required, null, null);
    }
    long late = deadline.until(eta, java.time.temporal.ChronoUnit.MONTHS);
    Status status = late > 0 ? Status.BEHIND : late < 0 ? Status.AHEAD : Status.ON_TRACK;
    return new GoalForecast(status, remaining, required, eta, late);
  }

  /** Месяц, в котором накопления дойдут до цели, или {@code null}. */
  static @Nullable YearMonth eta(
      BigDecimal current,
      BigDecimal target,
      BigDecimal monthlyRate,
      BigDecimal pace,
      YearMonth now) {
    if (pace.signum() <= 0 && (monthlyRate.signum() <= 0 || current.signum() <= 0)) {
      return null;
    }
    BigDecimal balance = current;
    for (int month = 1; month <= MAX_MONTHS; month++) {
      balance = balance.add(balance.multiply(monthlyRate, MC)).add(pace);
      if (balance.compareTo(target) >= 0) {
        return now.plusMonths(month);
      }
    }
    return null;
  }

  /**
   * Взнос в месяц, чтобы к концу месяца {@code deadline} накопить {@code target}. Взносы — каждый
   * месяц после текущего, включая месяц срока. Если успеваем на одних процентах — ноль.
   */
  static BigDecimal required(
      BigDecimal current,
      BigDecimal target,
      BigDecimal monthlyRate,
      YearMonth now,
      YearMonth deadline) {
    long months = Math.max(1, now.until(deadline, java.time.temporal.ChronoUnit.MONTHS));
    BigDecimal payment;
    if (monthlyRate.signum() == 0) {
      payment = target.subtract(current).divide(BigDecimal.valueOf(months), MC);
    } else {
      // current·(1+r)^n + x·((1+r)^n − 1)/r = target
      BigDecimal growth = BigDecimal.ONE.add(monthlyRate).pow((int) months, MC);
      BigDecimal annuity = growth.subtract(BigDecimal.ONE).divide(monthlyRate, MC);
      payment = target.subtract(current.multiply(growth, MC)).divide(annuity, MC);
    }
    return payment.max(BigDecimal.ZERO).setScale(2, RoundingMode.UP);
  }

  /** Сколько процентов цели накоплено, 0…100. */
  public static BigDecimal percent(BigDecimal current, BigDecimal target) {
    if (target.signum() <= 0) {
      return BigDecimal.ZERO;
    }
    return current
        .multiply(BigDecimal.valueOf(100))
        .divide(target, 1, RoundingMode.DOWN)
        .max(BigDecimal.ZERO)
        .min(BigDecimal.valueOf(100));
  }
}
