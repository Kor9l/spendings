package com.spendings.operation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Страница ленты: несколько дней целиком, чтобы итог дня не разрывался между страницами.
 *
 * @param nextBefore дата для следующей страницы или {@code null}, если дальше ничего нет
 */
public record Feed(List<Day> days, @Nullable LocalDate nextBefore) {

  public boolean isEmpty() {
    return days.isEmpty();
  }

  /** Итоги дня по валютам: расходы — отрицательные, доходы — положительные. */
  public record Day(
      LocalDate date,
      List<Item> items,
      Map<String, BigDecimal> expenses,
      Map<String, BigDecimal> income) {}

  /**
   * Операция в ленте, уже в виде для показа.
   *
   * @param amounts суммы со знаком, по одной на валюту (перевод между валютами — две)
   * @param balanceAfter остаток счёта после операции — только в выписке счёта
   */
  public record Item(
      UUID id,
      OperationType type,
      LocalDate date,
      String icon,
      String color,
      String title,
      @Nullable String details,
      List<Money> amounts,
      @Nullable String original,
      @Nullable BigDecimal balanceAfter) {}

  public record Money(BigDecimal amount, String currency) {}

  /** Условия выборки; все поля необязательны. */
  public record Filter(
      @Nullable LocalDate from,
      @Nullable LocalDate to,
      @Nullable UUID accountId,
      @Nullable UUID categoryId,
      @Nullable OperationType type,
      @Nullable String query) {

    public static Filter none() {
      return new Filter(null, null, null, null, null, null);
    }

    public Filter forAccount(UUID id) {
      return new Filter(from, to, id, categoryId, type, query);
    }
  }
}
