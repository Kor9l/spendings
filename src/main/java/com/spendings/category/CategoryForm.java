package com.spendings.category;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public class CategoryForm {

  @NotBlank
  @Size(max = 60)
  private String name = "";

  @NotNull private CategoryKind kind = CategoryKind.EXPENSE;

  private @Nullable UUID parentId;

  @Size(max = 16)
  private @Nullable String icon;

  @Pattern(regexp = "^$|^#[0-9a-fA-F]{6}$", message = "{category.colorPattern}")
  private @Nullable String color;

  static CategoryForm of(Category category) {
    var form = new CategoryForm();
    form.name = category.name();
    form.kind = category.kind();
    form.parentId = category.parentId();
    form.icon = category.icon();
    form.color = category.color();
    return form;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public CategoryKind getKind() {
    return kind;
  }

  public void setKind(CategoryKind kind) {
    this.kind = kind;
  }

  public @Nullable UUID getParentId() {
    return parentId;
  }

  public void setParentId(@Nullable UUID parentId) {
    this.parentId = parentId;
  }

  public @Nullable String getIcon() {
    return icon;
  }

  public void setIcon(@Nullable String icon) {
    this.icon = icon;
  }

  public @Nullable String getColor() {
    return color;
  }

  public void setColor(@Nullable String color) {
    this.color = color;
  }
}
