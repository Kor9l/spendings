package com.spendings.category;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "category")
class Category {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private @Nullable UUID id;

  @Column(name = "parent_id")
  private @Nullable UUID parentId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private CategoryKind kind;

  @Column(nullable = false)
  private String name;

  private @Nullable String icon;

  private @Nullable String color;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  @Column(name = "archived_at")
  private @Nullable Instant archivedAt;

  protected Category() {
    this(CategoryKind.EXPENSE, null, "", null, null, 0);
  }

  Category(
      CategoryKind kind,
      @Nullable UUID parentId,
      String name,
      @Nullable String icon,
      @Nullable String color,
      int sortOrder) {
    this.kind = kind;
    this.parentId = parentId;
    this.name = name;
    this.icon = icon;
    this.color = color;
    this.sortOrder = sortOrder;
  }

  void update(@Nullable UUID parentId, String name, @Nullable String icon, @Nullable String color) {
    this.parentId = parentId;
    this.name = name;
    this.icon = icon;
    this.color = color;
  }

  void moveUnder(UUID parentId, int sortOrder) {
    this.parentId = parentId;
    this.sortOrder = sortOrder;
  }

  void moveTo(int sortOrder) {
    this.sortOrder = sortOrder;
  }

  void archive() {
    if (archivedAt == null) {
      archivedAt = Instant.now();
    }
  }

  void restore() {
    archivedAt = null;
  }

  UUID id() {
    if (id == null) {
      throw new IllegalStateException("Категория ещё не сохранена");
    }
    return id;
  }

  @Nullable UUID parentId() {
    return parentId;
  }

  boolean isTopLevel() {
    return parentId == null;
  }

  CategoryKind kind() {
    return kind;
  }

  String name() {
    return name;
  }

  @Nullable String icon() {
    return icon;
  }

  @Nullable String color() {
    return color;
  }

  int sortOrder() {
    return sortOrder;
  }

  boolean archived() {
    return archivedAt != null;
  }
}
