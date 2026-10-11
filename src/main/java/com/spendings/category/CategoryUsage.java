package com.spendings.category;

import java.util.UUID;

/** Есть ли у категории операции. Реализует модуль операций. */
public interface CategoryUsage {

  boolean isUsed(UUID categoryId);
}
