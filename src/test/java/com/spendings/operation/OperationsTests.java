package com.spendings.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spendings.Fixtures;
import com.spendings.IntegrationTest;
import com.spendings.account.Accounts;
import com.spendings.operation.RecordOperation.AdjustBalance;
import com.spendings.operation.RecordOperation.RecordExpense;
import com.spendings.operation.RecordOperation.RecordIncome;
import com.spendings.operation.RecordOperation.RecordTransfer;
import com.spendings.operation.RecordOperation.Split;
import com.spendings.shared.DomainException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class OperationsTests {

  private static final LocalDate OCT_3 = LocalDate.of(2026, 10, 3);

  @Autowired Operations operations;
  @Autowired OperationFeed feed;
  @Autowired Accounts accounts;
  @Autowired Fixtures fixtures;

  UUID card;
  UUID carSavings;
  UUID groceries;
  UUID chemicals;
  UUID salary;

  @BeforeEach
  void setUp() {
    card = fixtures.account("Карта", "BYN", "1000.00");
    carSavings = fixtures.account("Машина", "USD", "0");
    groceries = fixtures.category("Еда", "Продукты");
    chemicals = fixtures.category("Дом", "Бытовая химия");
    salary = fixtures.incomeCategory("Зарплата");
  }

  @Test
  void expenseAndIncomeChangeTheBalance() {
    operations.record(expense(card, groceries, "62.10"));
    operations.record(
        new RecordIncome(card, OCT_3, List.of(new Split(salary, money("3200"))), "Работа", null));

    assertThat(accounts.balanceOf(card)).isEqualByComparingTo("4137.90");
  }

  @Test
  void splitExpenseBecomesOneOperationWithLinePerCategory() {
    UUID id =
        operations.record(
            new RecordExpense(
                card,
                OCT_3,
                List.of(new Split(groceries, money("62.10")), new Split(chemicals, money("25.30"))),
                "Супермаркет",
                null));

    assertThat(accounts.balanceOf(card)).isEqualByComparingTo("912.60");
    assertThat(fixtures.linesWithCategory(chemicals)).isEqualTo(1);

    Feed.Item item = feed.page(Feed.Filter.none(), null).days().getFirst().items().getFirst();
    assertThat(item.id()).isEqualTo(id);
    assertThat(item.title()).isEqualTo("Несколько категорий · 2");
    assertThat(item.amounts())
        .singleElement()
        .satisfies(
            m -> {
              assertThat(m.amount()).isEqualByComparingTo("-87.40");
              assertThat(m.currency()).isEqualTo("BYN");
            });
  }

  @Test
  void transferBetweenCurrenciesKeepsBothActualAmounts() {
    operations.record(
        new RecordTransfer(card, money("325.00"), carSavings, money("100.00"), OCT_3, null));

    assertThat(accounts.balanceOf(card)).isEqualByComparingTo("675.00");
    assertThat(accounts.balanceOf(carSavings)).isEqualByComparingTo("100.00");

    Feed.Item item = feed.page(Feed.Filter.none(), null).days().getFirst().items().getFirst();
    assertThat(item.title()).isEqualTo("Карта → Машина");
    assertThat(item.amounts()).extracting(Feed.Money::currency).containsExactly("BYN", "USD");
  }

  @Test
  void transferInOneCurrencyMustNotChangeTheAmount() {
    UUID cash = fixtures.account("Наличные", "BYN", "0");

    assertDomainError(
        () ->
            operations.record(
                new RecordTransfer(card, money("100"), cash, money("90"), OCT_3, null)),
        "operation.error.transferAmountsDiffer");
    assertDomainError(
        () ->
            operations.record(
                new RecordTransfer(card, money("100"), card, money("100"), OCT_3, null)),
        "operation.error.sameAccount");
  }

  @Test
  void rejectsWrongCategoryKindAndTooManyDecimals() {
    assertDomainError(
        () -> operations.record(expense(card, salary, "10")),
        "operation.error.categoryKindMismatch");
    assertDomainError(
        () -> operations.record(expense(card, groceries, "10.005")), "error.tooManyDecimals");
    assertDomainError(
        () -> operations.record(expense(card, groceries, "0")), "operation.error.amountPositive");
  }

  @Test
  void archivedAccountStaysInOldOperationsButCannotBeChosenAgain() {
    UUID id = operations.record(expense(card, groceries, "10"));
    fixtures.archiveAccount(card);

    assertDomainError(
        () -> operations.record(expense(card, groceries, "5")), "operation.error.accountArchived");

    Operations.Recorded recorded = operations.find(id);
    operations.update(id, recorded.version(), expense(card, groceries, "12"));
    assertThat(accounts.balanceOf(card)).isEqualByComparingTo("988.00");
  }

  @Test
  void staleVersionIsRejected() {
    UUID id = operations.record(expense(card, groceries, "10"));
    int version = operations.find(id).version();
    operations.update(id, version, expense(card, groceries, "11"));

    assertDomainError(
        () -> operations.update(id, version, expense(card, groceries, "12")),
        "error.concurrentEdit");
  }

  @Test
  void editKeepsTheCommandRoundTrip() {
    UUID id =
        operations.record(
            new RecordExpense(
                card,
                OCT_3,
                List.of(new Split(groceries, money("20.00"), money("6.00"), "USD", null)),
                "Amazon",
                "книга"));

    Operations.Recorded recorded = operations.find(id);
    assertThat(recorded.command())
        .isInstanceOfSatisfying(
            RecordExpense.class,
            e -> {
              assertThat(e.payee()).isEqualTo("Amazon");
              assertThat(e.splits())
                  .singleElement()
                  .satisfies(
                      s -> {
                        assertThat(s.amount()).isEqualByComparingTo("20");
                        assertThat(s.originalAmount()).isEqualByComparingTo("6");
                        assertThat(s.originalCurrency()).isEqualTo("USD");
                      });
            });
  }

  @Test
  void adjustmentCorrectsTheBalance() {
    operations.record(new AdjustBalance(card, OCT_3, money("-12.30"), "пересчёт"));

    assertThat(accounts.balanceOf(card)).isEqualByComparingTo("987.70");
    assertDomainError(
        () -> operations.record(new AdjustBalance(card, OCT_3, BigDecimal.ZERO, null)),
        "operation.error.adjustmentZero");
  }

  @Test
  void feedGroupsByDayAndFiltersByParentCategory() {
    operations.record(expense(card, groceries, "10"));
    operations.record(
        new RecordExpense(
            card, OCT_3.minusDays(1), List.of(new Split(chemicals, money("5"))), null, null));
    UUID cafe = fixtures.category("Еда", "Кафе и рестораны");
    operations.record(
        new RecordExpense(
            card, OCT_3.minusDays(1), List.of(new Split(cafe, money("7"))), "Кофейня", null));

    Feed all = feed.page(Feed.Filter.none(), null);
    assertThat(all.days()).extracting(Feed.Day::date).containsExactly(OCT_3, OCT_3.minusDays(1));
    assertThat(all.days().get(1).expenses()).containsEntry("BYN", new BigDecimal("-12.0000"));

    UUID food = fixtures.category("Еда");
    Feed onlyFood = feed.page(new Feed.Filter(null, null, null, food, null, null), null);
    assertThat(onlyFood.days()).flatExtracting(Feed.Day::items).hasSize(2);

    Feed byPayee = feed.page(new Feed.Filter(null, null, null, null, null, "кофе"), null);
    assertThat(byPayee.days()).flatExtracting(Feed.Day::items).hasSize(1);

    assertThat(feed.lastCategoryFor("кофейня", OperationType.EXPENSE)).contains(cafe);
    assertThat(feed.payees("коф")).containsExactly("Кофейня");
  }

  @Test
  void pagesByWholeDays() {
    for (int day = 0; day < 12; day++) {
      operations.record(
          new RecordExpense(
              card, OCT_3.minusDays(day), List.of(new Split(groceries, money("1"))), null, null));
    }

    Feed first = feed.page(Feed.Filter.none(), null);
    assertThat(first.days()).hasSize(OperationFeed.DAYS_PER_PAGE);
    assertThat(first.nextBefore()).isEqualTo(OCT_3.minusDays(9));

    Feed second = feed.page(Feed.Filter.none(), first.nextBefore());
    assertThat(second.days()).hasSize(2);
    assertThat(second.nextBefore()).isNull();
  }

  @Test
  void statementShowsRunningBalance() {
    operations.record(expense(card, groceries, "100"));
    operations.record(
        new RecordIncome(card, OCT_3, List.of(new Split(salary, money("50"))), null, null));

    List<Feed.Item> items =
        feed.page(Feed.Filter.none().forAccount(card), null).days().getFirst().items();
    // новые сверху: сначала доход, потом расход
    assertThat(items)
        .extracting(Feed.Item::balanceAfter)
        .usingElementComparator(BigDecimal::compareTo)
        .containsExactly(new BigDecimal("950"), new BigDecimal("900"));
  }

  private RecordExpense expense(UUID account, UUID category, String amount) {
    return new RecordExpense(
        account, OCT_3, List.of(new Split(category, money(amount))), null, null);
  }

  private static BigDecimal money(String value) {
    return new BigDecimal(value);
  }

  private static void assertDomainError(Runnable action, String messageKey) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            DomainException.class, e -> assertThat(e.messageKey()).isEqualTo(messageKey));
  }
}
