package com.spendings.operation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Команда ввода операции. Иерархия закрыта: {@code switch} по ней компилятор проверяет на полноту.
 * Все суммы — положительные; знак строкам расставляет {@link Operations}.
 */
public sealed interface RecordOperation {

  LocalDate date();

  @Nullable String note();

  /** Расход: со счёта, разбитый на одну или несколько категорий. */
  record RecordExpense(
      UUID accountId,
      LocalDate date,
      List<Split> splits,
      @Nullable String payee,
      @Nullable String note)
      implements RecordOperation {}

  record RecordIncome(
      UUID accountId,
      LocalDate date,
      List<Split> splits,
      @Nullable String payee,
      @Nullable String note)
      implements RecordOperation {}

  /** Перевод; если валюты счетов разные, суммы разные — так сохраняется фактический курс. */
  record RecordTransfer(
      UUID fromAccountId,
      BigDecimal fromAmount,
      UUID toAccountId,
      BigDecimal toAmount,
      LocalDate date,
      @Nullable String note)
      implements RecordOperation {}

  /** Корректировка остатка: {@code delta} со знаком. */
  record AdjustBalance(UUID accountId, LocalDate date, BigDecimal delta, @Nullable String note)
      implements RecordOperation {}

  /**
   * Часть расхода или дохода.
   *
   * @param originalAmount сумма в валюте покупки, если она не совпадает с валютой счёта
   */
  record Split(
      UUID categoryId,
      BigDecimal amount,
      @Nullable BigDecimal originalAmount,
      @Nullable String originalCurrency,
      @Nullable String note) {

    public Split(UUID categoryId, BigDecimal amount) {
      this(categoryId, amount, null, null, null);
    }
  }
}
