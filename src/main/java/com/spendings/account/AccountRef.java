package com.spendings.account;

import java.math.BigDecimal;
import java.util.UUID;

/** Счёт глазами других модулей. */
public record AccountRef(
    UUID id,
    String name,
    AccountType type,
    String currency,
    int minorUnits,
    BigDecimal openingBalance,
    boolean archived) {

  public String icon() {
    return type.icon();
  }
}
