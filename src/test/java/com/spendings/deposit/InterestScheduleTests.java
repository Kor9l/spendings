package com.spendings.deposit;

import static org.assertj.core.api.Assertions.assertThat;

import com.spendings.account.Capitalization;
import com.spendings.deposit.InterestSchedule.Period;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class InterestScheduleTests {

  private static final BigDecimal TEN_THOUSAND = new BigDecimal("10000");

  private static LocalDate d(String date) {
    return LocalDate.parse(date);
  }

  @Test
  void monthlyCapitalizationGrowsTheBase() {
    // открыт сегодня: всё в будущем, считается по прогнозу
    List<Period> periods =
        InterestSchedule.build(
            d("2026-10-01"),
            d("2027-10-01"),
            new BigDecimal("12"),
            Capitalization.MONTHLY,
            BigDecimal.ZERO,
            date -> TEN_THOUSAND,
            d("2026-10-01"));

    assertThat(periods).hasSize(12);
    assertThat(periods.getFirst().end()).isEqualTo(d("2026-11-01"));
    assertThat(periods.getFirst().gross())
        .isEqualByComparingTo("101.92"); // 10 000 × 12 % × 31 / 365
    assertThat(periods.get(1).base()).isEqualByComparingTo("10101.92");
    BigDecimal total = periods.stream().map(Period::net).reduce(BigDecimal.ZERO, BigDecimal::add);
    // по дням чуть больше, чем 1 268,25 «по 1 % в месяц» из плана
    assertThat(total).isBetween(new BigDecimal("1265"), new BigDecimal("1275"));
  }

  @Test
  void withoutCapitalizationTheBaseStaysAndTaxIsWithheld() {
    List<Period> periods =
        InterestSchedule.build(
            d("2026-10-01"),
            d("2027-04-01"),
            new BigDecimal("12"),
            Capitalization.NONE,
            new BigDecimal("13"),
            date -> TEN_THOUSAND,
            d("2026-10-01"));

    assertThat(periods)
        .hasSize(6)
        .allSatisfy(p -> assertThat(p.base()).isEqualByComparingTo("10000"));
    Period first = periods.getFirst();
    assertThat(first.tax()).isEqualByComparingTo("13.25"); // 13 % от 101,92
    assertThat(first.net()).isEqualByComparingTo("88.67");
  }

  @Test
  void atMaturityIsOnePeriodAndLeapYearDaysCountAs366() {
    List<Period> periods =
        InterestSchedule.build(
            d("2027-07-01"),
            d("2028-07-01"),
            new BigDecimal("10"),
            Capitalization.AT_MATURITY,
            BigDecimal.ZERO,
            date -> TEN_THOUSAND,
            d("2027-07-01"));

    assertThat(periods)
        .singleElement()
        .satisfies(
            p -> {
              assertThat(p.end()).isEqualTo(d("2028-07-01"));
              // 184 дня 2027 года / 365 + 182 дня 2028 года / 366 = 504,11 + 497,27
              assertThat(p.gross()).isEqualByComparingTo("1001.38");
            });
  }

  @Test
  void quarterlyAndLastShortPeriodEndsOnMaturity() {
    List<LocalDate> ends =
        InterestSchedule.periodEnds(d("2026-01-31"), d("2026-11-15"), Capitalization.QUARTERLY);
    assertThat(ends)
        .containsExactly(d("2026-04-30"), d("2026-07-31"), d("2026-10-31"), d("2026-11-15"));
  }

  @Test
  void pastPeriodsUseActualBalanceAndFutureStartsFromToday() {
    // до 15 ноября на вкладе 10 000, потом довнесли 5 000
    var balance =
        (java.util.function.Function<LocalDate, BigDecimal>)
            date -> date.isBefore(d("2026-11-15")) ? TEN_THOUSAND : new BigDecimal("15000");

    List<Period> periods =
        InterestSchedule.build(
            d("2026-10-01"),
            d("2027-02-01"),
            new BigDecimal("12"),
            Capitalization.MONTHLY,
            BigDecimal.ZERO,
            balance,
            d("2026-12-10"));

    assertThat(periods.get(0).base()).isEqualByComparingTo("10000"); // окт — по факту
    assertThat(periods.get(1).base()).isEqualByComparingTo("10000"); // ноя: на 1 ноября было 10 000
    assertThat(periods.get(2).base())
        .isEqualByComparingTo("15000"); // дек: идёт сейчас — от текущего остатка
    assertThat(periods.get(3).base())
        .isEqualByComparingTo(new BigDecimal("15000").add(periods.get(2).net()));
  }

  @Test
  void openEndedDepositIsShownAYearAhead() {
    List<Period> periods =
        InterestSchedule.build(
            d("2025-03-10"),
            null,
            new BigDecimal("5"),
            Capitalization.MONTHLY,
            BigDecimal.ZERO,
            date -> TEN_THOUSAND,
            d("2026-10-11"));

    assertThat(periods.getLast().end()).isAfter(d("2027-09-01"));
  }
}
