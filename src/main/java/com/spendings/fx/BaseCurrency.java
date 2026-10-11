package com.spendings.fx;

import com.spendings.shared.DomainException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Валюта, в которой считаются все итоги. Счета могут быть в любой валюте из справочника. */
@Service
public class BaseCurrency {

  private final JdbcClient jdbc;
  private final Currencies currencies;

  BaseCurrency(JdbcClient jdbc, Currencies currencies) {
    this.jdbc = jdbc;
    this.currencies = currencies;
  }

  /** Код ISO 4217, например {@code BYN}. */
  public String code() {
    return jdbc.sql("select base_currency from app_settings").query(String.class).single();
  }

  /** Прошлые операции не меняются: итоги просто пересчитываются в новую валюту. */
  @Transactional
  public void change(String code) {
    if (!currencies.exists(code)) {
      throw new DomainException("account.error.unknownCurrency", code);
    }
    jdbc.sql("update app_settings set base_currency = ?").param(code).update();
  }
}
