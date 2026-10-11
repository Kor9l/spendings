package com.spendings.fx;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Справочник валют ISO 4217. */
@Service
public class Currencies {

  private final JdbcClient jdbc;

  Currencies(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<Currency> all() {
    return jdbc.sql(
            "select code, name, minor_units from currency"
                + " order by case code when 'BYN' then 0 when 'USD' then 1 when 'EUR' then 2"
                + " else 3 end, code")
        .query(Currency.class)
        .list();
  }

  public boolean exists(String code) {
    return jdbc.sql("select count(*) from currency where code = ?")
            .param(code)
            .query(Integer.class)
            .single()
        > 0;
  }

  public record Currency(String code, String name, int minorUnits) {}
}
