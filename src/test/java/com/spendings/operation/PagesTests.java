package com.spendings.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import com.spendings.Fixtures;
import com.spendings.IntegrationTest;
import com.spendings.account.Accounts;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** Все страницы M1 рендерятся, а быстрый ввод через форму доходит до остатка. */
@IntegrationTest
class PagesTests {

  @Autowired MockMvcTester mvc;
  @Autowired Fixtures fixtures;
  @Autowired Accounts accounts;

  UUID card;
  UUID usd;

  @BeforeEach
  void setUp() {
    card = fixtures.account("Карта", "BYN", "100");
    usd = fixtures.account("Доллары", "USD", "0");
  }

  @Test
  void everyPageRenders() {
    for (String page :
        new String[] {
          "/",
          "/accounts",
          "/accounts/new",
          "/accounts/" + card,
          "/accounts/" + card + "/edit",
          "/categories",
          "/categories?kind=INCOME",
          "/categories/new",
          "/categories/" + fixtures.category("Еда") + "/edit",
          "/quick",
          "/quick?type=TRANSFER",
          "/quick?type=INCOME",
          "/operations"
        }) {
      assertThat(mvc.get().uri(page).with(user("owner"))).as(page).hasStatusOk();
    }
  }

  @Test
  void quickExpenseFromTheForm() {
    UUID fuel = fixtures.category("Транспорт", "Топливо");

    assertThat(
            mvc.post()
                .uri("/operations")
                .with(user("owner"))
                .with(csrf())
                .param("type", "EXPENSE")
                .param("accountId", card.toString())
                .param("amount", "85,40")
                .param("categoryId", fuel.toString())
                .param("date", "2026-10-08")
                .param("payee", "АЗС")
                .param("more", "1"))
        .hasStatus3xxRedirection()
        .redirectedUrl()
        .startsWith("/quick?type=EXPENSE&accountId=" + card);

    assertThat(accounts.balanceOf(card)).isEqualByComparingTo("14.60");
    assertThat(mvc.get().uri("/operations").with(user("owner")))
        .hasStatusOk()
        .bodyText()
        .contains("Транспорт › Топливо")
        .contains("АЗС");
  }

  @Test
  void formErrorsAreShownNextToTheForm() {
    assertThat(
            mvc.post()
                .uri("/operations")
                .with(user("owner"))
                .with(csrf())
                .param("type", "EXPENSE")
                .param("accountId", card.toString())
                .param("amount", "abc")
                .param("date", "2026-10-08"))
        .hasStatusOk()
        .bodyText()
        .contains("Введите сумму числом")
        .contains("Выберите категорию");
  }

  @Test
  void crossCurrencyTransferFromTheForm() {
    assertThat(
            mvc.post()
                .uri("/operations")
                .with(user("owner"))
                .with(csrf())
                .param("type", "TRANSFER")
                .param("accountId", card.toString())
                .param("amount", "32,50")
                .param("toAccountId", usd.toString())
                .param("toAmount", "10")
                .param("date", "2026-10-08"))
        .hasStatus3xxRedirection();

    assertThat(accounts.balanceOf(card)).isEqualByComparingTo("67.50");
    assertThat(accounts.balanceOf(usd)).isEqualByComparingTo("10");
  }

  @Test
  void reconcileCreatesAnAdjustment() {
    assertThat(
            mvc.post()
                .uri("/accounts/" + card + "/reconcile")
                .with(user("owner"))
                .with(csrf())
                .param("actualBalance", "87,70"))
        .hasStatus3xxRedirection();

    assertThat(accounts.balanceOf(card)).isEqualByComparingTo("87.70");
  }

  @Test
  void accountFormCreatesADeposit() {
    assertThat(
            mvc.post()
                .uri("/accounts")
                .with(user("owner"))
                .with(csrf())
                .param("name", "Вклад 12%")
                .param("type", "DEPOSIT")
                .param("currency", "BYN")
                .param("openingBalance", "10 000")
                .param("openedOn", "2026-10-01")
                .param("annualRate", "12,5")
                .param("capitalization", "MONTHLY")
                .param("maturesOn", "2027-10-01"))
        .hasStatus3xxRedirection()
        .hasRedirectedUrl("/accounts");

    assertThat(accounts.active())
        .anySatisfy(
            a -> {
              assertThat(a.name()).isEqualTo("Вклад 12%");
              assertThat(a.openingBalance()).isEqualByComparingTo("10000");
            });
  }
}
