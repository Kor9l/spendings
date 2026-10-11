package com.spendings.category;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spendings.Fixtures;
import com.spendings.IntegrationTest;
import com.spendings.operation.Operations;
import com.spendings.operation.RecordOperation.RecordExpense;
import com.spendings.operation.RecordOperation.Split;
import com.spendings.shared.DomainException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class CategoriesTests {

  @Autowired Categories categories;
  @Autowired Operations operations;
  @Autowired Fixtures fixtures;

  @Test
  void templateHasTwoLevelsAndSubcategoriesInheritIconAndColor() {
    List<Categories.CategoryNode> expenses = categories.tree(CategoryKind.EXPENSE, false);
    Categories.CategoryNode food = expenses.getFirst();

    assertThat(food.category().name()).isEqualTo("Еда");
    assertThat(food.children())
        .extracting(CategoryRef::name)
        .containsExactly("Продукты", "Кафе и рестораны", "Доставка");
    CategoryRef groceries = food.children().getFirst();
    assertThat(groceries.icon()).isEqualTo(food.category().icon());
    assertThat(groceries.color()).isEqualTo(food.category().color());
    assertThat(groceries.displayName()).isEqualTo("Еда › Продукты");
  }

  @Test
  void onlyTwoLevelsAndKindsDoNotMix() {
    UUID groceries = fixtures.category("Еда", "Продукты");
    assertDomainError(
        () -> categories.create(form("Овощи", CategoryKind.EXPENSE, groceries)),
        "category.error.tooDeep");

    UUID salary = fixtures.incomeCategory("Зарплата");
    assertDomainError(
        () -> categories.create(form("Обеды", CategoryKind.EXPENSE, salary)),
        "category.error.parentKindMismatch");

    assertDomainError(
        () -> categories.create(form("продукты", CategoryKind.EXPENSE, fixtures.category("Еда"))),
        "category.error.nameTaken");
  }

  @Test
  void mergeMovesOperationsAndChildrenAndMergesSameNamedChildren() {
    UUID card = fixtures.account("Карта", "BYN", "0");
    UUID food = fixtures.category("Еда");
    UUID home = fixtures.category("Дом");
    UUID foodRepair =
        categories.create(form("Ремонт", CategoryKind.EXPENSE, food)); // есть и в «Дом»
    UUID groceries = fixtures.category("Еда", "Продукты");
    record(card, foodRepair);
    record(card, groceries);
    record(card, food);

    categories.merge(food, home);

    assertThat(categories.find(food)).isEmpty();
    assertThat(categories.find(foodRepair)).isEmpty();
    UUID homeRepair = fixtures.category("Дом", "Ремонт");
    assertThat(fixtures.linesWithCategory(homeRepair)).isEqualTo(1);
    assertThat(fixtures.linesWithCategory(home)).isEqualTo(1);
    assertThat(categories.get(groceries).parentId()).isEqualTo(home);
    assertThat(fixtures.linesWithCategory(groceries)).isEqualTo(1);
  }

  @Test
  void usedCategoryCannotBeDeletedOnlyArchived() {
    UUID card = fixtures.account("Карта", "BYN", "0");
    UUID cafe = fixtures.category("Еда", "Кафе и рестораны");
    record(card, cafe);

    assertDomainError(() -> categories.delete(cafe), "category.error.used");

    categories.archive(cafe);
    assertThat(categories.get(cafe).archived()).isTrue();
    assertThat(categories.tree(CategoryKind.EXPENSE, false).getFirst().children())
        .extracting(CategoryRef::id)
        .doesNotContain(cafe);
  }

  @Test
  void unusedCategoryCanBeDeleted() {
    UUID delivery = fixtures.category("Еда", "Доставка");
    categories.delete(delivery);
    assertThat(categories.find(delivery)).isEmpty();
  }

  private void record(UUID account, UUID category) {
    operations.record(
        new RecordExpense(
            account,
            LocalDate.of(2026, 10, 1),
            List.of(new Split(category, BigDecimal.TEN)),
            null,
            null));
  }

  private static CategoryForm form(String name, CategoryKind kind, UUID parentId) {
    var form = new CategoryForm();
    form.setName(name);
    form.setKind(kind);
    form.setParentId(parentId);
    return form;
  }

  private static void assertDomainError(Runnable action, String messageKey) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            DomainException.class, e -> assertThat(e.messageKey()).isEqualTo(messageKey));
  }
}
