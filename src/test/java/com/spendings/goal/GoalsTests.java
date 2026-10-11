package com.spendings.goal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import com.spendings.Fixtures;
import com.spendings.IntegrationTest;
import com.spendings.account.Accounts;
import com.spendings.goal.Goal.Funding;
import com.spendings.goal.Goal.Kind;
import com.spendings.operation.Operations;
import com.spendings.operation.RecordOperation.RecordExpense;
import com.spendings.operation.RecordOperation.RecordTransfer;
import com.spendings.operation.RecordOperation.Split;
import com.spendings.shared.DomainException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@IntegrationTest
class GoalsTests {

  private static final YearMonth NOW = YearMonth.now();

  @Autowired Goals goals;
  @Autowired Operations operations;
  @Autowired Accounts accounts;
  @Autowired Fixtures fixtures;
  @Autowired JdbcClient jdbc;
  @Autowired MockMvcTester mvc;

  UUID card;
  UUID deposit;

  @BeforeEach
  void setUp() {
    card = fixtures.account("Карта", "USD", "5000");
    deposit = fixtures.account("Вклад на машину", "USD", "7600");
    jdbc.sql(
            "insert into deposit_terms (account_id, annual_rate, capitalization, opened_on)"
                + " values (?, 3, 'MONTHLY', ?)")
        .params(deposit, NOW.minusMonths(6).atDay(1))
        .update();
    // три полных месяца по 600 на вклад
    for (int m = 3; m >= 1; m--) {
      operations.record(
          new RecordTransfer(
              card, money("600"), deposit, money("600"), NOW.minusMonths(m).atDay(10), null));
    }
  }

  @Test
  void accountGoalTracksBalanceInterestAndPace() {
    UUID id =
        goals.create(
            form(
                "Новая машина",
                "25000",
                "USD",
                NOW.plusMonths(20),
                Funding.ACCOUNTS,
                Map.of(deposit, "100")));

    Goals.GoalView goal = goals.get(id);
    assertThat(goal.current()).isEqualByComparingTo("9400");
    assertThat(goal.percent()).isEqualByComparingTo("37.6");
    assertThat(goal.annualRate()).isEqualByComparingTo("3");
    assertThat(goal.pace()).isEqualByComparingTo("600");
    assertThat(goal.forecast()).isNotNull();
    assertThat(goal.forecast().status()).isEqualTo(GoalForecast.Status.BEHIND);
    assertThat(goal.forecast().eta()).isEqualTo(NOW.plusMonths(25));
    assertThat(goal.forecast().requiredMonthly()).isBetween(money("735"), money("742"));
    assertThat(goals.whatIf(id, money("800"))).isBefore(goal.forecast().eta());
  }

  @Test
  void accountCanBeSharedButNotOverAllocated() {
    goals.create(form("Машина", "20000", "USD", null, Funding.ACCOUNTS, Map.of(deposit, "60")));
    UUID vacation =
        goals.create(form("Отпуск", "3000", "USD", null, Funding.ACCOUNTS, Map.of(deposit, "40")));

    assertThat(goals.get(vacation).current()).isEqualByComparingTo("3760"); // 40 % от 9400

    assertThatThrownBy(
            () ->
                goals.create(
                    form("Ещё", "100", "USD", null, Funding.ACCOUNTS, Map.of(deposit, "1"))))
        .isInstanceOfSatisfying(
            DomainException.class,
            e -> assertThat(e.messageKey()).isEqualTo("goal.error.overAllocated"));
  }

  @Test
  void accountsInOtherCurrencyAreConvertedToGoalCurrency() {
    UUID byn = fixtures.account("Конверт BYN", "BYN", "3000");
    jdbc.sql(
            "insert into exchange_rate (rate_date, base, quote, rate, source) values (?, 'USD', 'BYN', 3, 'NBRB')")
        .param(LocalDate.now().minusDays(1))
        .update();

    UUID id =
        goals.create(
            form(
                "Машина",
                "25000",
                "USD",
                null,
                Funding.ACCOUNTS,
                Map.of(deposit, "100", byn, "100")));

    assertThat(goals.get(id).current()).isEqualByComparingTo("10400"); // 9400 USD + 3000 BYN / 3
    assertThat(goals.get(id).missingRates()).isFalse();
  }

  @Test
  void virtualReserveCountsMarksOnly() {
    UUID id = goals.create(form("Отпуск", "2000", "USD", null, Funding.MANUAL, Map.of()));
    goals.contribute(id, NOW.minusMonths(1).atDay(5), money("300"), "аванс");
    goals.contribute(id, LocalDate.now(), money("-50"), "взял на билеты");

    Goals.GoalView goal = goals.get(id);
    assertThat(goal.current()).isEqualByComparingTo("250");
    assertThat(goal.accounts()).isEmpty();
    assertThat(goals.contributionsOf(id)).hasSize(2);
    assertThatThrownBy(
            () ->
                goals.contribute(
                    goals.create(
                        form("Счётная", "10", "USD", null, Funding.ACCOUNTS, Map.of(card, "100"))),
                    LocalDate.now(),
                    money("1"),
                    null))
        .isInstanceOf(DomainException.class);
  }

  @Test
  void emergencyFundTargetFollowsAverageExpenses() {
    UUID food = fixtures.category("Еда", "Продукты");
    for (int m = 1; m <= 2; m++) {
      operations.record(
          new RecordExpense(
              card,
              NOW.minusMonths(m).atDay(3),
              List.of(new Split(food, money("1000"))),
              null,
              null));
    }

    var form = form("Подушка", null, "USD", null, Funding.MANUAL, Map.of());
    form.setKind(Kind.MONTHS_OF_EXPENSES);
    form.setTargetMonths(6);
    UUID id = goals.create(form);

    Goals.GoalView goal = goals.get(id);
    // расходы были в двух полных месяцах из последних двенадцати: в среднем 1000 в месяц
    assertThat(goal.averageExpenses()).isEqualByComparingTo("1000");
    assertThat(goal.target()).isEqualByComparingTo("6000");
  }

  @Test
  void paceUsesCompleteMonthsAfterTheFirstContribution() {
    Map<YearMonth, BigDecimal> history = new LinkedHashMap<>();
    history.put(NOW.minusMonths(3), BigDecimal.ZERO);
    history.put(NOW.minusMonths(2), BigDecimal.ZERO);
    history.put(NOW.minusMonths(1), money("400"));
    history.put(NOW, money("100"));
    assertThat(Goals.pace(history, NOW)).isEqualByComparingTo("400");

    Map<YearMonth, BigDecimal> fresh = new LinkedHashMap<>();
    fresh.put(NOW.minusMonths(1), BigDecimal.ZERO);
    fresh.put(NOW, money("250"));
    assertThat(Goals.pace(fresh, NOW)).as("полных месяцев ещё нет").isEqualByComparingTo("250");
  }

  @Test
  void pagesRenderAndFormCreatesAGoal() {
    assertThat(mvc.get().uri("/goals").with(user("owner"))).hasStatusOk();
    assertThat(mvc.get().uri("/goals/new").with(user("owner")))
        .hasStatusOk()
        .bodyText()
        .contains("Вклад на машину");

    assertThat(
            mvc.post()
                .uri("/goals")
                .with(user("owner"))
                .with(csrf())
                .param("name", "Новая машина")
                .param("icon", "🚗")
                .param("kind", "AMOUNT")
                .param("targetAmount", "25 000")
                .param("currency", "USD")
                .param("targetDate", NOW.plusMonths(20).atEndOfMonth().toString())
                .param("funding", "ACCOUNTS")
                .param("priority", "1")
                .param("accounts[0].accountId", card.toString())
                .param("accounts[0].sharePct", "100")
                .param("accounts[1].accountId", deposit.toString())
                .param("accounts[1].selected", "true")
                .param("accounts[1].sharePct", "100"))
        .hasStatus3xxRedirection();

    UUID id = goals.active().getFirst().id();
    assertThat(mvc.get().uri("/goals/" + id).with(user("owner")))
        .hasStatusOk()
        .bodyText()
        .contains("Новая машина")
        .contains("37,6%")
        .contains("отстаю на 5 мес.");
    assertThat(mvc.get().uri("/goals/" + id + "/edit").with(user("owner"))).hasStatusOk();
    assertThat(mvc.get().uri("/goals/" + id + "/what-if?monthly=1000").with(user("owner")))
        .hasStatusOk()
        .bodyText()
        .contains("цель будет достигнута");
    assertThat(mvc.get().uri("/").with(user("owner")))
        .hasStatusOk()
        .bodyText()
        .contains("Новая машина");
  }

  private static GoalForm form(
      String name,
      String target,
      String currency,
      YearMonth deadline,
      Funding funding,
      Map<UUID, String> shares) {
    var form = new GoalForm();
    form.setName(name);
    form.setTargetAmount(target == null ? null : money(target));
    form.setCurrency(currency);
    form.setTargetDate(deadline == null ? null : deadline.atEndOfMonth());
    form.setFunding(funding);
    shares.forEach(
        (accountId, share) -> {
          var row = new GoalForm.ShareRow();
          row.setAccountId(accountId);
          row.setSelected(true);
          row.setSharePct(money(share));
          form.getAccounts().add(row);
        });
    return form;
  }

  private static BigDecimal money(String value) {
    return new BigDecimal(value);
  }
}
