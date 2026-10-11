package com.spendings.account;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.NavigableMap;
import java.util.UUID;

/**
 * Сумма движений по счетам. Реализует модуль операций: так у счёта есть остаток, а модуль счетов не
 * зависит от операций.
 */
public interface AccountMovements {

  /** Сумма строк проведённых операций по каждому счёту, у которого они есть. */
  Map<UUID, BigDecimal> totals();

  boolean hasMovements(UUID accountId);

  /** Изменение остатка счёта по дням (проведённые операции), по возрастанию даты. */
  NavigableMap<LocalDate, BigDecimal> dailyChanges(UUID accountId);
}
