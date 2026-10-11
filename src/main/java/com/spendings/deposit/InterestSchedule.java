package com.spendings.deposit;

import com.spendings.account.Capitalization;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Year;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * График начислений по вкладу. Чистая функция.
 *
 * <ul>
 *   <li>Периоды отсчитываются от даты открытия: ежемесячно — каждый месяц в тот же день (31-е →
 *       последний день месяца), ежеквартально — раз в три месяца, в конце срока — один период.
 *   <li>Проценты за период = остаток × ставка × дни / дней в году; дни, попавшие в високосный год,
 *       делятся на 366.
 *   <li>С капитализацией проценты после налога прибавляются к остатку и сами приносят проценты; без
 *       капитализации («выплата») остаток не растёт, проценты выплачиваются раз в месяц.
 * </ul>
 */
public final class InterestSchedule {

  /** Без даты окончания график строится на год вперёд. */
  static final int OPEN_ENDED_MONTHS = 12;

  static final int MAX_PERIODS = 240;

  private static final MathContext MC = MathContext.DECIMAL64;
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private InterestSchedule() {}

  /**
   * @param balanceAt фактический остаток вклада на дату (для прошедших периодов)
   * @param today периоды до сегодняшнего дня считаются по фактическому остатку, дальше — по
   *     прогнозу с капитализацией
   */
  public static List<Period> build(
      LocalDate openedOn,
      @Nullable LocalDate maturesOn,
      BigDecimal annualRatePct,
      Capitalization capitalization,
      BigDecimal taxRatePct,
      Function<LocalDate, BigDecimal> balanceAt,
      LocalDate today) {
    LocalDate end = maturesOn != null ? maturesOn : horizon(openedOn, today);
    List<LocalDate> ends = periodEnds(openedOn, end, capitalization);
    List<Period> periods = new ArrayList<>();
    LocalDate start = openedOn;
    BigDecimal projected = null;
    for (LocalDate periodEnd : ends) {
      BigDecimal base;
      if (!periodEnd.isAfter(today) || projected == null) {
        base = balanceAt.apply(start);
        if (periodEnd.isAfter(today)) {
          projected = balanceAt.apply(today);
          base = projected;
        }
      } else {
        base = projected;
      }
      BigDecimal gross = interest(base, annualRatePct, start, periodEnd);
      BigDecimal tax =
          gross.multiply(taxRatePct, MC).divide(HUNDRED, MC).setScale(2, RoundingMode.HALF_UP);
      BigDecimal net = gross.subtract(tax);
      periods.add(new Period(start, periodEnd, scale(base), gross, tax, net));
      if (periodEnd.isAfter(today) && capitalization != Capitalization.NONE) {
        projected = projected.add(net);
      }
      start = periodEnd;
    }
    return periods;
  }

  /** Проценты за [from; to) по ставке, с учётом високосных лет. */
  static BigDecimal interest(
      BigDecimal balance, BigDecimal annualRatePct, LocalDate from, LocalDate to) {
    BigDecimal total = BigDecimal.ZERO;
    LocalDate cursor = from;
    while (cursor.isBefore(to)) {
      LocalDate yearEnd = cursor.withDayOfYear(1).plusYears(1);
      LocalDate segmentEnd = yearEnd.isBefore(to) ? yearEnd : to;
      long days = ChronoUnit.DAYS.between(cursor, segmentEnd);
      int yearDays = Year.of(cursor.getYear()).length();
      total =
          total.add(
              balance
                  .multiply(annualRatePct, MC)
                  .multiply(BigDecimal.valueOf(days), MC)
                  .divide(BigDecimal.valueOf(yearDays * 100L), MC));
      cursor = segmentEnd;
    }
    return total.setScale(2, RoundingMode.HALF_UP);
  }

  static List<LocalDate> periodEnds(
      LocalDate openedOn, LocalDate end, Capitalization capitalization) {
    List<LocalDate> ends = new ArrayList<>();
    if (capitalization == Capitalization.AT_MATURITY) {
      ends.add(end);
      return ends;
    }
    int step = capitalization == Capitalization.QUARTERLY ? 3 : 1;
    for (int i = 1; i <= MAX_PERIODS; i++) {
      LocalDate next = openedOn.plusMonths((long) i * step);
      if (!next.isBefore(end)) {
        ends.add(end);
        break;
      }
      ends.add(next);
    }
    return ends;
  }

  private static LocalDate horizon(LocalDate openedOn, LocalDate today) {
    LocalDate horizon = openedOn.plusMonths(OPEN_ENDED_MONTHS);
    while (!horizon.isAfter(today.plusMonths(OPEN_ENDED_MONTHS - 1L))) {
      horizon = horizon.plusMonths(OPEN_ENDED_MONTHS);
    }
    return horizon;
  }

  private static BigDecimal scale(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_UP);
  }

  /**
   * Период начисления: проценты начисляются в день {@code end}.
   *
   * @param base остаток, на который начисляются проценты
   * @param net проценты после налога — их и зачисляют
   */
  public record Period(
      LocalDate start,
      LocalDate end,
      BigDecimal base,
      BigDecimal gross,
      BigDecimal tax,
      BigDecimal net) {}
}
