package com.spendings.fx;

import java.time.LocalDate;
import java.util.Optional;

/** С какой даты нужны курсы: самая ранняя операция или начало учёта. Реализует модуль операций. */
public interface RateDemand {

  Optional<LocalDate> earliestDate();
}
