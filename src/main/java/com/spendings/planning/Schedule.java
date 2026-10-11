package com.spendings.planning;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Расписание регулярной операции. Чистая функция: даты исполнений в заданном интервале.
 *
 * <ul>
 *   <li>Ежемесячно в день N: если в месяце меньше дней — в последний день (31-е → 30 апреля, 28/29
 *       февраля).
 *   <li>Еженедельно в день недели, считая от недели начала.
 *   <li>Ежегодно в дату начала; 29 февраля в невисокосный год — 28-го.
 * </ul>
 */
public record Schedule(
    Frequency frequency,
    int interval,
    @Nullable Integer dayOfMonth,
    @Nullable Integer dayOfWeek,
    LocalDate startsOn,
    @Nullable LocalDate endsOn) {

  public enum Frequency {
    MONTHLY,
    WEEKLY,
    YEARLY
  }

  /** Даты исполнений в {@code [from, to]}, по возрастанию. */
  public List<LocalDate> between(LocalDate from, LocalDate to) {
    List<LocalDate> dates = new ArrayList<>();
    LocalDate last = endsOn == null || endsOn.isAfter(to) ? to : endsOn;
    if (last.isBefore(from) || last.isBefore(startsOn)) {
      return dates;
    }
    switch (frequency) {
      case MONTHLY -> {
        int day = dayOfMonth == null ? startsOn.getDayOfMonth() : dayOfMonth;
        YearMonth first = YearMonth.from(startsOn);
        long skip =
            Math.max(0, first.until(YearMonth.from(from), ChronoUnit.MONTHS) / interval - 1);
        for (YearMonth m = first.plusMonths(skip * interval);
            !m.atDay(1).isAfter(last);
            m = m.plusMonths(interval)) {
          add(dates, m.atDay(Math.min(day, m.lengthOfMonth())), from, last);
        }
      }
      case WEEKLY -> {
        DayOfWeek weekday =
            DayOfWeek.of(dayOfWeek == null ? startsOn.getDayOfWeek().getValue() : dayOfWeek);
        LocalDate first = startsOn.with(TemporalAdjusters.nextOrSame(weekday));
        long skip = Math.max(0, first.until(from, ChronoUnit.WEEKS) / interval - 1);
        for (LocalDate d = first.plusWeeks(skip * interval);
            !d.isAfter(last);
            d = d.plusWeeks(interval)) {
          add(dates, d, from, last);
        }
      }
      case YEARLY -> {
        for (int year = startsOn.getYear(); year <= last.getYear(); year += interval) {
          YearMonth m = YearMonth.of(year, startsOn.getMonth());
          add(dates, m.atDay(Math.min(startsOn.getDayOfMonth(), m.lengthOfMonth())), from, last);
        }
      }
    }
    return dates;
  }

  private void add(List<LocalDate> dates, LocalDate date, LocalDate from, LocalDate last) {
    if (!date.isBefore(from) && !date.isAfter(last) && !date.isBefore(startsOn)) {
      dates.add(date);
    }
  }

  /**
   * Бюджетный месяц, который начинается {@code startDay}-го: например, «октябрь» при старте 5-го —
   * это 5 октября … 4 ноября.
   */
  public record BudgetPeriod(YearMonth month, LocalDate from, LocalDate to) {

    public static BudgetPeriod of(YearMonth month, int startDay) {
      LocalDate from = month.atDay(startDay);
      return new BudgetPeriod(month, from, month.plusMonths(1).atDay(startDay).minusDays(1));
    }

    /** Бюджетный месяц, в который попадает дата. */
    public static BudgetPeriod containing(LocalDate date, int startDay) {
      YearMonth month = YearMonth.from(date);
      return of(date.getDayOfMonth() >= startDay ? month : month.minusMonths(1), startDay);
    }

    public BudgetPeriod previous() {
      return of(month.minusMonths(1), from.getDayOfMonth());
    }

    public BudgetPeriod next() {
      return of(month.plusMonths(1), from.getDayOfMonth());
    }
  }
}
