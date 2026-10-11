package com.spendings.fx;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Пересчёт между валютами по курсу на дату: берётся ближайший курс не позже даты. Если курса нет,
 * результат пустой — курс 1 никогда не подставляется.
 */
@Service
@Transactional(readOnly = true)
public class ExchangeRates {

  private static final MathContext PRECISION = new MathContext(20);

  private final JdbcClient jdbc;

  ExchangeRates(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Сколько единиц {@code to} стоит 1 единица {@code from}. */
  public Optional<BigDecimal> rate(String from, String to, LocalDate date) {
    if (from.equals(to)) {
      return Optional.of(BigDecimal.ONE);
    }
    return jdbc.sql("select fx_rate(?, ?, ?)")
        .params(from, to, date)
        .query(BigDecimal.class)
        .optional();
  }

  public Optional<BigDecimal> convert(BigDecimal amount, String from, String to, LocalDate date) {
    return rate(from, to, date).map(r -> amount.multiply(r, PRECISION));
  }

  /** Последний известный курс каждой валюты к pivot — для страницы настроек. */
  public List<KnownRate> latest() {
    return jdbc.sql(
            """
            select distinct on (base) base, quote, rate_date, rate, source
            from exchange_rate
            order by base, rate_date desc
            """)
        .query(KnownRate.class)
        .list();
  }

  /** Ручной курс за 1 единицу {@code base} в pivot. Перекрывает курс провайдера на эту дату. */
  @Transactional
  public void saveManual(String base, String quote, LocalDate date, BigDecimal rate) {
    jdbc.sql(
            """
            insert into exchange_rate (rate_date, base, quote, rate, source)
            values (?, ?, ?, ?, 'MANUAL')
            on conflict (base, quote, rate_date)
            do update set rate = excluded.rate, source = 'MANUAL'
            """)
        .params(date, base, quote, rate)
        .update();
  }

  public record KnownRate(
      String base, String quote, LocalDate rateDate, BigDecimal rate, String source) {}
}
