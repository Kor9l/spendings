package com.spendings.account;

/** Как вклад начисляет проценты: без капитализации они выплачиваются, а остаток не растёт. */
public enum Capitalization {
  NONE,
  MONTHLY,
  QUARTERLY,
  AT_MATURITY
}
