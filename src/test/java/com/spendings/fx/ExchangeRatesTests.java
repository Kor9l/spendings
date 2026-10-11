package com.spendings.fx;

import static org.assertj.core.api.Assertions.assertThat;

import com.spendings.IntegrationTest;
import com.spendings.fx.RateProvider.Rate;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
class ExchangeRatesTests {

  private static final LocalDate OCT_5 = LocalDate.of(2026, 10, 5);

  @Autowired ExchangeRates rates;
  @Autowired Currencies currencies;
  @Autowired JdbcClient jdbc;

  @Test
  void convertsThroughThePivotWithTheLatestEarlierRate() {
    rate("USD", "2026-10-01", "3.0000");
    rate("USD", "2026-10-04", "3.0051");
    rate("EUR", "2026-10-04", "3.3860");

    // курс на 5-е — ближайший не позже даты, то есть 4-го
    assertThat(rates.rate("USD", "BYN", OCT_5))
        .hasValueSatisfying(r -> assertThat(r).isEqualByComparingTo("3.0051"));
    assertThat(rates.rate("BYN", "USD", OCT_5))
        .hasValueSatisfying(
            r ->
                assertThat(r)
                    .isCloseTo(
                        new BigDecimal("0.33276"),
                        org.assertj.core.data.Offset.offset(new BigDecimal("0.00001"))));
    // кросс-курс USD → EUR через BYN
    assertThat(rates.convert(new BigDecimal("100"), "USD", "EUR", OCT_5))
        .hasValueSatisfying(
            v ->
                assertThat(v)
                    .isCloseTo(
                        new BigDecimal("88.75"),
                        org.assertj.core.data.Offset.offset(new BigDecimal("0.01"))));
    assertThat(rates.rate("USD", "BYN", LocalDate.of(2026, 10, 2)))
        .hasValueSatisfying(r -> assertThat(r).isEqualByComparingTo("3.0000"));
  }

  @Test
  void missingRateIsEmptyNeverOne() {
    assertThat(rates.rate("RUB", "BYN", OCT_5)).isEmpty();
    rate("USD", "2026-10-10", "3.01");
    assertThat(rates.rate("USD", "BYN", OCT_5)).as("курса раньше даты нет").isEmpty();
    assertThat(rates.rate("BYN", "BYN", OCT_5)).contains(BigDecimal.ONE);
  }

  @Test
  void manualRateReplacesTheProviderRateForThatDay() {
    rate("USD", "2026-10-05", "3.0073");
    rates.saveManual("USD", "BYN", OCT_5, new BigDecimal("3.10"));

    assertThat(rates.rate("USD", "BYN", OCT_5))
        .hasValueSatisfying(r -> assertThat(r).isEqualByComparingTo("3.10"));
  }

  @Test
  void syncFillsOnlyMissingDaysAndKeepsManualRates() {
    rates.saveManual("USD", "BYN", LocalDate.of(2026, 10, 3), new BigDecimal("3.50"));
    var provider = new FakeProvider();
    Clock clock = Clock.fixed(OCT_5.atStartOfDay(ZoneId.of("UTC")).toInstant(), ZoneId.of("UTC"));
    var sync =
        new RateSync(
            provider, () -> Optional.of(LocalDate.of(2026, 10, 1)), currencies, jdbc, clock);

    int added = sync.sync();

    // USD, EUR, RUB: сегодня и завтра + история 1…5 октября, кроме уже известных дней
    assertThat(added).isGreaterThan(0);
    assertThat(rates.rate("USD", "BYN", LocalDate.of(2026, 10, 3)))
        .as("ручной курс не перезаписан")
        .hasValueSatisfying(r -> assertThat(r).isEqualByComparingTo("3.50"));
    assertThat(rates.rate("EUR", "BYN", LocalDate.of(2026, 10, 2))).isPresent();
    assertThat(provider.historyCalls).contains("USD 2026-10-01..2026-10-02");

    assertThat(sync.sync()).as("повторный запуск ничего не добавляет").isZero();
  }

  @Test
  void gapsAreContinuousRanges() {
    LocalDate d1 = LocalDate.of(2026, 10, 1);
    List<LocalDate[]> gaps =
        RateSync.gaps(d1, d1.plusDays(5), Set.of(d1.plusDays(2), d1.plusDays(3)));
    assertThat(gaps).hasSize(2);
    assertThat(gaps.get(0)).containsExactly(d1, d1.plusDays(1));
    assertThat(gaps.get(1)).containsExactly(d1.plusDays(4), d1.plusDays(5));
  }

  private void rate(String currency, String date, String value) {
    jdbc.sql(
            "insert into exchange_rate (rate_date, base, quote, rate, source) values (?, ?, 'BYN', ?, 'NBRB')")
        .params(LocalDate.parse(date), currency, new BigDecimal(value))
        .update();
  }

  /** Отдаёт по курсу на каждый запрошенный день. */
  static class FakeProvider implements RateProvider {

    final List<String> historyCalls = new ArrayList<>();

    @Override
    public String source() {
      return "NBRB";
    }

    @Override
    public String pivot() {
      return "BYN";
    }

    @Override
    public int maxHistoryDays() {
      return 365;
    }

    @Override
    public List<Rate> ratesOn(LocalDate date) {
      return List.of(
          new Rate(date, "USD", new BigDecimal("3.0")),
          new Rate(date, "EUR", new BigDecimal("3.4")),
          new Rate(date, "RUB", new BigDecimal("0.036")),
          new Rate(date, "CNY", new BigDecimal("0.42")));
    }

    @Override
    public List<Rate> history(String currency, LocalDate from, LocalDate to) {
      historyCalls.add(currency + " " + from + ".." + to);
      List<Rate> rates = new ArrayList<>();
      for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
        rates.add(new Rate(d, currency, new BigDecimal("3.0")));
      }
      return rates;
    }
  }
}
