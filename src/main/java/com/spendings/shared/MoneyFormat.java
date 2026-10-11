package com.spendings.shared;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Деньги в шаблонах: {@code ${@money.format(amount, 'BYN')}}. */
@Component("money")
public class MoneyFormat {

  public String format(@Nullable BigDecimal amount, String currency) {
    return amount == null ? "" : Amounts.format(amount) + " " + currency;
  }

  public String signed(BigDecimal amount, String currency) {
    return Amounts.formatSigned(amount) + " " + currency;
  }

  /** Процент с одним знаком после запятой: {@code 37,6}. */
  public String percent(@Nullable BigDecimal value) {
    return value == null
        ? ""
        : value.setScale(1, java.math.RoundingMode.HALF_UP).toPlainString().replace('.', ',');
  }

  public String input(@Nullable BigDecimal amount) {
    return Amounts.forInput(amount);
  }
}
