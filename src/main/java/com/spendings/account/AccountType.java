package com.spendings.account;

public enum AccountType {
  CASH("💵"),
  CARD("💳"),
  SAVINGS("🐷"),
  DEPOSIT("🏦"),
  CREDIT("🧾"),
  OTHER("📁");

  private final String icon;

  AccountType(String icon) {
    this.icon = icon;
  }

  public String icon() {
    return icon;
  }

  /** Ключ названия в {@code messages.properties}. */
  public String labelKey() {
    return "account.type." + name();
  }
}
