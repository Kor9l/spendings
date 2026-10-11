package com.spendings.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import com.spendings.Fixtures;
import com.spendings.IntegrationTest;
import com.spendings.account.Accounts;
import com.spendings.operation.OperationType;
import com.spendings.operation.Operations;
import com.spendings.operation.RecordOperation.RecordExpense;
import com.spendings.operation.RecordOperation.Split;
import com.spendings.planning.Schedule.Frequency;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@IntegrationTest
class PlanningTests {

  private static final LocalDate TODAY = LocalDate.now();

  @Autowired Planner planner;
  @Autowired Budgets budgets;
  @Autowired Operations operations;
  @Autowired Accounts accounts;
  @Autowired Fixtures fixtures;
  @Autowired JdbcClient jdbc;
  @Autowired MockMvcTester mvc;

  UUID card;
  UUID utilities;
  UUID electricity;
  UUID groceries;

  @BeforeEach
  void setUp() {
    card = fixtures.account("Карта", "BYN", "1000");
    utilities = fixtures.category("Жильё и коммуналка");
    electricity = fixtures.category("Жильё и коммуналка", "Электроэнергия");
    groceries = fixtures.category("Еда", "Продукты");
  }

  @Test
  void dueRuleCanBePostedWithActualAmountOrSkipped() {
    LocalDate due = TODAY.plusDays(3);
    UUID rule = planner.create(monthly("Свет", electricity, "100", due));

    assertThat(planner.upcoming(30))
        .anySatisfy(
            d -> {
              assertThat(d.ruleId()).isEqualTo(rule);
              assertThat(d.date()).isEqualTo(due);
              assertThat(d.overdue()).isFalse();
            });

    planner.post(rule, due, money("112.40"), null, due);
    assertThat(planner.upcoming(30))
        .noneMatch(d -> d.ruleId().equals(rule) && d.date().equals(due));
    assertThat(accounts.balanceOf(card)).isEqualByComparingTo("887.60");

    LocalDate nextMonth = due.plusMonths(1);
    planner.skip(
        rule, nextMonth.withDayOfMonth(Math.min(due.getDayOfMonth(), nextMonth.lengthOfMonth())));
    assertThat(
            planner.due(
                nextMonth.withDayOfMonth(1), nextMonth.withDayOfMonth(nextMonth.lengthOfMonth())))
        .noneMatch(d -> d.ruleId().equals(rule));
  }

  @Test
  void manualOperationClosesThePlannedPaymentWithinToleranceAndWindow() {
    LocalDate due = TODAY.minusDays(1);
    UUID rule = planner.create(monthly("Свет", electricity, "100", due));

    // сумма вне допуска 10 % — платёж остаётся ожидаемым
    operations.record(expense(electricity, "125", due));
    assertThat(planner.upcoming(30)).anyMatch(d -> d.ruleId().equals(rule) && d.date().equals(due));

    // через 3 дня после срока и на 8 % больше — закрывает
    UUID op = operations.record(expense(electricity, "108", due.plusDays(3)));
    assertThat(planner.upcoming(30))
        .noneMatch(d -> d.ruleId().equals(rule) && d.date().equals(due));

    // удалили операцию — платёж снова ожидается
    operations.delete(op);
    jdbc.sql("select 1").query(Integer.class).single();
    assertThat(planner.upcoming(30)).anyMatch(d -> d.ruleId().equals(rule) && d.date().equals(due));
  }

  @Test
  void importedOperationAlsoClosesThePlannedPayment() {
    LocalDate due = TODAY.minusDays(2);
    UUID rule = planner.create(monthly("Свет", electricity, "100", due));
    UUID batch =
        jdbc.sql(
                "insert into import_batch (account_id, file_name) values (?, 'v.csv') returning id")
            .param(card)
            .query(UUID.class)
            .single();

    operations.recordImported(expense(groceries, "95", due), batch, "key-1");

    assertThat(planner.upcoming(30))
        .noneMatch(d -> d.ruleId().equals(rule) && d.date().equals(due));
  }

  @Test
  void autoPostPostsDueRulesOnly() {
    RuleForm subscription =
        monthly(
            "Подписка",
            fixtures.category("Связь и подписки", "Подписки"),
            "15",
            TODAY.minusDays(5));
    subscription.setAutoPost(true);
    planner.create(subscription);
    planner.create(monthly("Свет", electricity, "100", TODAY.minusDays(4)));

    assertThat(planner.autoPost(TODAY)).isEqualTo(1);
    assertThat(accounts.balanceOf(card)).isEqualByComparingTo("985");
    assertThat(planner.autoPost(TODAY)).as("повторно ничего").isZero();
  }

  @Test
  void forecastAddsPlannedIncomeAndSubtractsPlannedExpenses() {
    RuleForm salary =
        monthly("Зарплата", fixtures.incomeCategory("Зарплата"), "1500", TODAY.plusDays(2));
    salary.setType(OperationType.INCOME);
    planner.create(salary);
    planner.create(monthly("Свет", electricity, "100", TODAY.plusDays(3)));

    Planner.Forecast forecast = planner.forecast(TODAY.plusDays(5));
    assertThat(forecast.now()).isEqualByComparingTo("1000");
    assertThat(forecast.plannedIncome()).isEqualByComparingTo("1500");
    assertThat(forecast.plannedExpenses()).isEqualByComparingTo("100");
    assertThat(forecast.end()).isEqualByComparingTo("2400");
  }

  @Test
  void budgetCountsSubcategoriesPlannedPaymentsAndCarry() {
    YearMonth month = YearMonth.of(2026, 9);
    YearMonth previous = month.minusMonths(1);
    budgets.save(utilities, previous, money("300"), true);
    budgets.save(utilities, month, money("300"), false);
    budgets.save(groceries, month, money("200"), false);
    operations.record(
        expense(
            electricity, "250", LocalDate.of(2026, 8, 20))); // в августе не потрачено 50 → перенос
    operations.record(expense(electricity, "140", LocalDate.of(2026, 9, 10)));
    operations.record(expense(groceries, "190", LocalDate.of(2026, 9, 12)));
    operations.record(
        expense(fixtures.category("Транспорт", "Топливо"), "60", LocalDate.of(2026, 9, 14)));

    Budgets.BudgetView view = budgets.view(month);

    Budgets.BudgetRow housing =
        view.rows().stream()
            .filter(r -> r.category().id().equals(utilities))
            .findFirst()
            .orElseThrow();
    assertThat(housing.carry()).isEqualByComparingTo("50");
    assertThat(housing.available()).isEqualByComparingTo("350");
    assertThat(housing.spent()).isEqualByComparingTo("140");
    assertThat(housing.level()).isEqualTo("success");

    Budgets.BudgetRow food =
        view.rows().stream()
            .filter(r -> r.category().id().equals(groceries))
            .findFirst()
            .orElseThrow();
    assertThat(food.percent()).isEqualByComparingTo("95.0");
    assertThat(food.level()).isEqualTo("warning");
    assertThat(view.unbudgeted()).isEqualByComparingTo("60");
    assertThat(view.totalLimit()).isEqualByComparingTo("550");

    assertThat(budgets.copyFromPrevious(month.plusMonths(1))).isEqualTo(2);
  }

  @Test
  void budgetMonthCanStartOnPayday() {
    budgets.changeStartDay(5);
    budgets.save(groceries, YearMonth.of(2026, 9), money("100"), false);
    operations.record(expense(groceries, "30", LocalDate.of(2026, 9, 4))); // ещё «август»
    operations.record(expense(groceries, "40", LocalDate.of(2026, 10, 4))); // ещё «сентябрь»

    Budgets.BudgetView september = budgets.view(YearMonth.of(2026, 9));
    assertThat(september.period().from()).isEqualTo(LocalDate.of(2026, 9, 5));
    assertThat(september.rows().getFirst().spent()).isEqualByComparingTo("40");
  }

  @Test
  void pagesRenderAndRuleFormWorks() {
    assertThat(
            mvc.post()
                .uri("/planning/rules")
                .with(user("owner"))
                .with(csrf())
                .param("name", "Коммуналка")
                .param("type", "EXPENSE")
                .param("amount", "180")
                .param("accountId", card.toString())
                .param("categoryId", utilities.toString())
                .param("frequency", "MONTHLY")
                .param("interval", "1")
                .param("dayOfMonth", "31")
                .param("startsOn", TODAY.minusMonths(1).toString())
                .param("tolerancePct", "15"))
        .hasStatus3xxRedirection()
        .hasRedirectedUrl("/planning");

    assertThat(mvc.get().uri("/planning").with(user("owner")))
        .hasStatusOk()
        .bodyText()
        .contains("Коммуналка")
        .contains("31-го");
    assertThat(mvc.get().uri("/planning?month=2027-02").with(user("owner"))).hasStatusOk();
    assertThat(mvc.get().uri("/planning/rules/new?type=TRANSFER").with(user("owner")))
        .hasStatusOk();
    UUID rule = planner.rules().getFirst().id();
    assertThat(mvc.get().uri("/planning/rules/" + rule + "/edit").with(user("owner")))
        .hasStatusOk();

    assertThat(
            mvc.post()
                .uri("/budgets")
                .with(user("owner"))
                .with(csrf())
                .param("month", YearMonth.now().toString())
                .param("categoryId", groceries.toString())
                .param("amount", "400")
                .param("rollover", "true"))
        .hasStatus3xxRedirection();
    assertThat(mvc.get().uri("/budgets").with(user("owner")))
        .hasStatusOk()
        .bodyText()
        .contains("Продукты");
    assertThat(mvc.get().uri("/").with(user("owner")))
        .hasStatusOk()
        .bodyText()
        .contains("Коммуналка");
  }

  private RuleForm monthly(String name, UUID category, String amount, LocalDate due) {
    var form = new RuleForm();
    form.setName(name);
    form.setType(OperationType.EXPENSE);
    form.setAccountId(card);
    form.setCategoryId(category);
    form.setAmount(money(amount));
    form.setFrequency(Frequency.MONTHLY);
    form.setDayOfMonth(due.getDayOfMonth());
    form.setStartsOn(due);
    return form;
  }

  private RecordExpense expense(UUID category, String amount, LocalDate date) {
    return new RecordExpense(card, date, List.of(new Split(category, money(amount))), null, null);
  }

  private static BigDecimal money(String value) {
    return new BigDecimal(value);
  }
}
