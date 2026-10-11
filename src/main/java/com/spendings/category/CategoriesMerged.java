package com.spendings.category;

import java.util.UUID;

/**
 * Категорию {@code source} объединили с {@code target}: всё, что ссылалось на source, должно
 * ссылаться на target. Публикуется внутри транзакции объединения; слушатели обрабатывают его
 * синхронно, до удаления source.
 */
public record CategoriesMerged(UUID source, UUID target) {}
