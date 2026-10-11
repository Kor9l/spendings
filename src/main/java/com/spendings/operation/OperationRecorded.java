package com.spendings.operation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Записана новая операция — вручную или из выписки. Публикуется внутри транзакции записи;
 * планирование по нему сопоставляет операцию с ожидаемым регулярным платежом.
 *
 * @param accountId счёт первой строки (для перевода — счёт списания)
 * @param amount сумма первой строки со знаком, в валюте счёта
 * @param categoryId категория первой строки; у перевода — {@code null}
 */
public record OperationRecorded(
    UUID operationId,
    OperationType type,
    LocalDate date,
    UUID accountId,
    BigDecimal amount,
    @Nullable UUID categoryId) {}
