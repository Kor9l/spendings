package com.spendings.fx;

import com.spendings.fx.NbrbApi.DailyRate;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.resilience.annotation.Retryable;

/**
 * Курсы НБРБ. Курс дан за {@code Cur_Scale} единиц (RUB — за 100), поэтому делим на масштаб.
 * Идентификатор и масштаб валюты берутся из дневного списка: в истории их нет.
 */
class NbrbRateProvider implements RateProvider {

  private static final MathContext PRECISION = new MathContext(16);

  private final NbrbApi api;
  private final Map<String, DailyRate> latestByCode = new ConcurrentHashMap<>();

  NbrbRateProvider(NbrbApi api) {
    this.api = api;
  }

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
  @Retryable(maxRetries = 3, delay = 2000, multiplier = 2)
  public List<Rate> ratesOn(LocalDate date) {
    List<DailyRate> daily = api.rates(date);
    daily.forEach(r -> latestByCode.put(r.code(), r));
    return daily.stream()
        .map(r -> new Rate(r.date().toLocalDate(), r.code(), perUnit(r.rate(), r.scale())))
        .toList();
  }

  @Override
  @Retryable(maxRetries = 3, delay = 2000, multiplier = 2)
  public List<Rate> history(String currency, LocalDate from, LocalDate to) {
    DailyRate reference = latestByCode.get(currency);
    if (reference == null) {
      ratesOn(LocalDate.now());
      reference = latestByCode.get(currency);
      if (reference == null) {
        return List.of(); // НБРБ эту валюту не котирует
      }
    }
    int scale = reference.scale();
    return api.dynamics(reference.id(), from, to).stream()
        .map(r -> new Rate(r.date().toLocalDate(), currency, perUnit(r.rate(), scale)))
        .toList();
  }

  static BigDecimal perUnit(BigDecimal rate, int scale) {
    return scale == 1 ? rate : rate.divide(BigDecimal.valueOf(scale), PRECISION);
  }
}
