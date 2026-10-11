package com.spendings.fx;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Источник официальных курсов. Курсы — к валюте {@link #pivot()} за 1 единицу. */
interface RateProvider {

  String source();

  String pivot();

  /** Курсы всех доступных валют на дату. */
  List<Rate> ratesOn(LocalDate date);

  /** История курса одной валюты, включительно. Не длиннее {@link #maxHistoryDays()} дней. */
  List<Rate> history(String currency, LocalDate from, LocalDate to);

  int maxHistoryDays();

  record Rate(LocalDate date, String currency, BigDecimal perUnit) {}
}
