package com.spendings.fx;

import com.spendings.fx.RateProvider.Rate;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * Загружает курсы, которых не хватает: от самой ранней операции до завтрашнего дня (НБРБ публикует
 * курс на завтра днём). Уже загруженные дни и ручные курсы не трогает.
 */
class RateSync {

  private static final Logger log = LoggerFactory.getLogger(RateSync.class);
  static final int DEFAULT_HISTORY_DAYS = 365;

  private final RateProvider provider;
  private final RateDemand demand;
  private final Currencies currencies;
  private final JdbcClient jdbc;
  private final Clock clock;

  RateSync(
      RateProvider provider,
      RateDemand demand,
      Currencies currencies,
      JdbcClient jdbc,
      Clock clock) {
    this.provider = provider;
    this.demand = demand;
    this.currencies = currencies;
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /**
   * @return сколько курсов добавлено
   */
  @Transactional
  int sync() {
    LocalDate today = LocalDate.now(clock);
    LocalDate from =
        demand
            .earliestDate()
            .filter(d -> d.isBefore(today))
            .orElse(today.minusDays(DEFAULT_HISTORY_DAYS));
    int saved = 0;

    // курсы на сегодня и завтра одним списком на всё
    for (LocalDate day : List.of(today, today.plusDays(1))) {
      try {
        saved += save(provider.ratesOn(day).stream().filter(r -> wanted(r.currency())).toList());
      } catch (RuntimeException e) {
        log.warn("Курсы {} на {} не загружены: {}", provider.source(), day, e.getMessage());
      }
    }

    for (String currency : wantedCurrencies()) {
      Set<LocalDate> known = knownDates(currency, from, today);
      for (LocalDate[] gap : gaps(from, today, known)) {
        for (LocalDate chunk = gap[0];
            !chunk.isAfter(gap[1]);
            chunk = chunk.plusDays(provider.maxHistoryDays())) {
          LocalDate chunkEnd = min(gap[1], chunk.plusDays(provider.maxHistoryDays() - 1L));
          try {
            saved += save(provider.history(currency, chunk, chunkEnd));
          } catch (RuntimeException e) {
            log.warn(
                "История {} {} {}…{} не загружена: {}",
                provider.source(),
                currency,
                chunk,
                chunkEnd,
                e.getMessage());
          }
        }
      }
    }
    if (saved > 0) {
      log.info("Загружено курсов {}: {}", provider.source(), saved);
    }
    return saved;
  }

  private boolean wanted(String currency) {
    return wantedCurrencies().contains(currency);
  }

  private List<String> wantedCurrencies() {
    return currencies.all().stream()
        .map(Currencies.Currency::code)
        .filter(c -> !c.equals(provider.pivot()))
        .toList();
  }

  private Set<LocalDate> knownDates(String currency, LocalDate from, LocalDate to) {
    return new HashSet<>(
        jdbc.sql(
                "select rate_date from exchange_rate where base = ? and quote = ?"
                    + " and rate_date between ? and ?")
            .params(currency, provider.pivot(), from, to)
            .query(LocalDate.class)
            .list());
  }

  /** Непрерывные отрезки дат без курса. */
  static List<LocalDate[]> gaps(LocalDate from, LocalDate to, Set<LocalDate> known) {
    var gaps = new ArrayList<LocalDate[]>();
    LocalDate start = null;
    for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
      if (!known.contains(d)) {
        if (start == null) {
          start = d;
        }
      } else if (start != null) {
        gaps.add(new LocalDate[] {start, d.minusDays(1)});
        start = null;
      }
    }
    if (start != null) {
      gaps.add(new LocalDate[] {start, to});
    }
    return gaps;
  }

  private int save(List<Rate> rates) {
    int saved = 0;
    for (Rate rate : rates) {
      saved +=
          jdbc.sql(
                  """
                  insert into exchange_rate (rate_date, base, quote, rate, source)
                  values (?, ?, ?, ?, ?)
                  on conflict (base, quote, rate_date) do nothing
                  """)
              .params(
                  rate.date(), rate.currency(), provider.pivot(), rate.perUnit(), provider.source())
              .update();
    }
    return saved;
  }

  private static LocalDate min(LocalDate a, LocalDate b) {
    return a.isBefore(b) ? a : b;
  }
}
