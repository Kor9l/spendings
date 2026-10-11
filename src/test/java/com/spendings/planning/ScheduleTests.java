package com.spendings.planning;

import static org.assertj.core.api.Assertions.assertThat;

import com.spendings.planning.Schedule.BudgetPeriod;
import com.spendings.planning.Schedule.Frequency;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class ScheduleTests {

  private static LocalDate d(String date) {
    return LocalDate.parse(date);
  }

  @Test
  void the31stFallsOnTheLastDayOfShortMonths() {
    var schedule = new Schedule(Frequency.MONTHLY, 1, 31, null, d("2027-01-01"), null);

    assertThat(schedule.between(d("2027-01-01"), d("2027-05-31")))
        .containsExactly(
            d("2027-01-31"), d("2027-02-28"), d("2027-03-31"), d("2027-04-30"), d("2027-05-31"));
  }

  @Test
  void februaryInALeapYear() {
    var schedule = new Schedule(Frequency.MONTHLY, 1, 30, null, d("2028-01-01"), null);

    assertThat(schedule.between(d("2028-02-01"), d("2028-03-31")))
        .containsExactly(d("2028-02-29"), d("2028-03-30"));
  }

  @Test
  void everyThirdMonthCountsFromTheStart() {
    var quarterly = new Schedule(Frequency.MONTHLY, 3, 10, null, d("2026-02-01"), null);

    assertThat(quarterly.between(d("2026-06-01"), d("2027-03-31")))
        .containsExactly(d("2026-08-10"), d("2026-11-10"), d("2027-02-10"));
  }

  @Test
  void startAndEndDatesAreRespected() {
    var schedule = new Schedule(Frequency.MONTHLY, 1, 5, null, d("2026-10-07"), d("2027-01-04"));

    // 5 октября — до начала, 5 января — после конца
    assertThat(schedule.between(d("2026-09-01"), d("2027-02-28")))
        .containsExactly(d("2026-11-05"), d("2026-12-05"));
  }

  @Test
  void weeklyOnAGivenDayEveryOtherWeek() {
    // 11.10.2026 — воскресенье; платим по пятницам раз в две недели
    var schedule = new Schedule(Frequency.WEEKLY, 2, null, 5, d("2026-10-11"), null);

    assertThat(schedule.between(d("2026-10-01"), d("2026-11-30")))
        .containsExactly(d("2026-10-16"), d("2026-10-30"), d("2026-11-13"), d("2026-11-27"));
  }

  @Test
  void yearlyOnFebruary29thInCommonYears() {
    var schedule = new Schedule(Frequency.YEARLY, 1, null, null, d("2028-02-29"), null);

    assertThat(schedule.between(d("2028-01-01"), d("2030-12-31")))
        .containsExactly(d("2028-02-29"), d("2029-02-28"), d("2030-02-28"));
  }

  @Test
  void farFutureWindowIsComputedWithoutWalkingFromTheStart() {
    var schedule = new Schedule(Frequency.MONTHLY, 1, 1, null, d("2000-01-01"), null);

    assertThat(schedule.between(d("2026-10-01"), d("2026-10-31"))).containsExactly(d("2026-10-01"));
  }

  @Test
  void budgetMonthStartingOnPayday() {
    BudgetPeriod october = BudgetPeriod.of(YearMonth.of(2026, 10), 5);
    assertThat(october.from()).isEqualTo(d("2026-10-05"));
    assertThat(october.to()).isEqualTo(d("2026-11-04"));

    assertThat(BudgetPeriod.containing(d("2026-10-04"), 5).month())
        .isEqualTo(YearMonth.of(2026, 9));
    assertThat(BudgetPeriod.containing(d("2026-10-05"), 5).month())
        .isEqualTo(YearMonth.of(2026, 10));
    assertThat(BudgetPeriod.of(YearMonth.of(2026, 10), 1).to()).isEqualTo(d("2026-10-31"));
    assertThat(october.previous().from()).isEqualTo(d("2026-09-05"));
  }
}
