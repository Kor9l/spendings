package com.spendings.goal;

import static org.assertj.core.api.Assertions.assertThat;

import com.spendings.goal.GoalForecast.Status;
import java.math.BigDecimal;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class GoalForecastTests {

  private static final YearMonth OCT_2026 = YearMonth.of(2026, 10);

  /** Пример из плана: docs/02-development-plan.md, раздел 4.4. */
  @Test
  void newCarExampleFromThePlan() {
    GoalForecast f =
        GoalForecast.of(
            money("9400"),
            money("25000"),
            money("3"),
            money("600"),
            OCT_2026,
            YearMonth.of(2028, 6));

    assertThat(f.status()).isEqualTo(Status.BEHIND);
    assertThat(f.eta()).isEqualTo(YearMonth.of(2028, 11));
    assertThat(f.monthsLate()).isEqualTo(5);
    assertThat(f.requiredMonthly()).isBetween(money("735"), money("742")); // ≈ 740
    assertThat(f.remaining()).isEqualByComparingTo("15600");
  }

  @Test
  void withoutInterestRequiredIsJustRemainingByMonths() {
    GoalForecast f =
        GoalForecast.of(
            money("9400"),
            money("25000"),
            BigDecimal.ZERO,
            money("600"),
            OCT_2026,
            YearMonth.of(2028, 6));

    assertThat(f.requiredMonthly()).isEqualByComparingTo("780");
  }

  @Test
  void aheadAndOnTrack() {
    assertThat(
            GoalForecast.of(
                    money("0"),
                    money("1200"),
                    BigDecimal.ZERO,
                    money("200"),
                    OCT_2026,
                    YearMonth.of(2027, 6))
                .status())
        .isEqualTo(Status.AHEAD); // 6 месяцев вместо 8
    assertThat(
            GoalForecast.of(
                    money("0"),
                    money("1200"),
                    BigDecimal.ZERO,
                    money("100"),
                    OCT_2026,
                    YearMonth.of(2027, 10))
                .status())
        .isEqualTo(Status.ON_TRACK);
  }

  @Test
  void achievedWhenCurrentReachesTarget() {
    GoalForecast f =
        GoalForecast.of(
            money("1500"), money("1000"), BigDecimal.ZERO, BigDecimal.ZERO, OCT_2026, null);
    assertThat(f.status()).isEqualTo(Status.ACHIEVED);
    assertThat(f.remaining()).isEqualByComparingTo("0");
  }

  @Test
  void noPaceMeansNoEta() {
    GoalForecast f =
        GoalForecast.of(
            money("100"),
            money("1000"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            OCT_2026,
            YearMonth.of(2027, 1));
    assertThat(f.status()).isEqualTo(Status.NO_PACE);
    assertThat(f.eta()).isNull();
    assertThat(f.requiredMonthly()).isEqualByComparingTo("300");
  }

  @Test
  void interestAloneCanReachTheGoal() {
    // 10 000 под 12 % без взносов дорастают до 11 000 за 10 месяцев
    GoalForecast f =
        GoalForecast.of(
            money("10000"), money("11000"), money("12"), BigDecimal.ZERO, OCT_2026, null);
    assertThat(f.status()).isEqualTo(Status.NO_DEADLINE);
    assertThat(f.eta()).isEqualTo(YearMonth.of(2027, 8));
  }

  @Test
  void pastDeadlineAsksForEverythingAtOnce() {
    GoalForecast f =
        GoalForecast.of(
            money("200"),
            money("1000"),
            BigDecimal.ZERO,
            money("100"),
            OCT_2026,
            YearMonth.of(2026, 8));
    assertThat(f.requiredMonthly()).isEqualByComparingTo("800");
    assertThat(f.status()).isEqualTo(Status.BEHIND);
  }

  @Test
  void percentIsClamped() {
    assertThat(GoalForecast.percent(money("9400"), money("25000"))).isEqualByComparingTo("37.6");
    assertThat(GoalForecast.percent(money("-5"), money("100"))).isEqualByComparingTo("0");
    assertThat(GoalForecast.percent(money("150"), money("100"))).isEqualByComparingTo("100");
  }

  private static BigDecimal money(String value) {
    return new BigDecimal(value);
  }
}
