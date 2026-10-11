package com.spendings.category;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Категория глазами других модулей. Иконка и цвет уже с учётом наследования от родителя.
 *
 * @param parentName имя родителя, если это подкатегория
 */
public record CategoryRef(
    UUID id,
    @Nullable UUID parentId,
    @Nullable String parentName,
    CategoryKind kind,
    String name,
    String icon,
    String color,
    boolean archived) {

  /** Корень дерева: сама категория или её родитель. */
  public UUID topLevelId() {
    return parentId == null ? id : parentId;
  }

  /** «Еда › Кафе» или просто «Еда». */
  public String displayName() {
    return parentName == null ? name : parentName + " › " + name;
  }
}
