package com.spendings.fx;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Валюта, в которой считаются все итоги. Счета могут быть в любой валюте из справочника. */
@Service
public class BaseCurrency {

  private final JdbcClient jdbc;

  BaseCurrency(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Код ISO 4217, например {@code BYN}. */
  public String code() {
    return jdbc.sql("select base_currency from app_settings").query(String.class).single();
  }
}
