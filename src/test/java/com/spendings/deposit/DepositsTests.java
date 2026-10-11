package com.spendings.deposit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import com.spendings.Fixtures;
import com.spendings.IntegrationTest;
import com.spendings.account.Accounts;
import com.spendings.deposit.Deposits.Due;
import com.spendings.deposit.Deposits.Row;
import com.spendings.deposit.Deposits.State;
import com.spendings.operation.OperationStats;
import com.spendings.operation.Operations;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@IntegrationTest
class DepositsTests {

  private static final LocalDate TODAY = LocalDate.now();

  /** Открыт три с лишним месяца назад: три ежемесячных периода закончились. */
  private static final LocalDate OPENED = TODAY.minusMonths(3).minusDays(5);

  @Autowired Deposits deposits;
  @Autowired Accounts accounts;
  @Autowired Operations operations;
  @Autowired OperationStats stats;
  @Autowired Fixtures fixtures;
  @Autowired JdbcClient jdbc;
  @Autowired MockMvcTester mvc;

  UUID card;
  UUID deposit;

  @BeforeEach
  void setUp() {
    card = fixtures.account("Карта", "BYN", "500");
    deposit = deposit("Годовой", "MONTHLY", null);
  }

  @Test
  void endedPeriodsAreDueAndPostingRecordsInterestIncomeOnTheDeposit() {
    List<Due> due = dueOf(deposit);
    assertThat(due).hasSize(3);
    Due first = due.getFirst();
    assertThat(first.period().start()).isEqualTo(OPENED);
    assertThat(first.period().base()).isEqualByComparingTo("10000");

    UUID operationId =
        deposits.post(deposit, first.period().end(), money("101.50"), first.period().end());

    assertThat(dueOf(deposit)).hasSize(2);
    assertThat(accounts.balanceOf(deposit)).isEqualByComparingTo("10101.50");
    assertThat(
            jdbc.sql("select source from operation where id = ?")
                .param(operationId)
                .query(String.class)
                .single())
        .isEqualTo("INTEREST");
    assertThat(fixtures.linesWithCategory(fixtures.incomeCategory("Проценты по вкладам")))
        .isEqualTo(1);

    Deposits.DepositView view = deposits.view(deposit);
    Row posted = view.rows().getFirst();
    assertThat(posted.state()).isEqualTo(State.POSTED);
    assertThat(posted.actual()).isEqualByComparingTo("101.50");
    assertThat(posted.actualDiffers()).isTrue();
    // следующий период — от остатка вместе с процентами
    assertThat(view.rows().get(1).period().base()).isEqualByComparingTo("10101.50");
    assertThat(view.received()).isEqualByComparingTo("101.50");
  }

  @Test
  void skippedAccrualCanBeReturnedAndDeletingTheIncomeMakesItDueAgain() {
    LocalDate firstEnd = dueOf(deposit).getFirst().period().end();
    LocalDate secondEnd = dueOf(deposit).get(1).period().end();

    deposits.skip(deposit, secondEnd);
    assertThat(dueOf(deposit)).extracting(d -> d.period().end()).doesNotContain(secondEnd);
    assertThat(deposits.view(deposit).rows().get(1).state()).isEqualTo(State.SKIPPED);
    deposits.unskip(deposit, secondEnd);
    assertThat(dueOf(deposit)).extracting(d -> d.period().end()).contains(secondEnd);

    UUID operationId = deposits.post(deposit, firstEnd, money("100"), firstEnd);
    assertThat(dueOf(deposit)).hasSize(2);
    operations.delete(operationId);
    assertThat(dueOf(deposit)).hasSize(3);
  }

  @Test
  void withoutCapitalizationInterestGoesToThePayoutAccount() {
    UUID paid = deposit("С выплатой", "NONE", card);
    Due due = dueOf(paid).getFirst();

    deposits.post(paid, due.period().end(), due.period().net(), due.period().end());

    assertThat(due.payTo().id()).isEqualTo(card);
    assertThat(accounts.balanceOf(paid)).isEqualByComparingTo("10000");
    assertThat(accounts.balanceOf(card))
        .isEqualByComparingTo(new BigDecimal("500").add(due.period().net()));
    // база не растёт: все периоды — от 10 000
    assertThat(deposits.view(paid).rows())
        .allSatisfy(r -> assertThat(r.period().base()).isEqualByComparingTo("10000"));
  }

  @Test
  void periodsBeforeTheDepositWasAddedAreAlreadyInTheOpeningBalance() {
    jdbc.sql("update account set created_at = ? where id = ?")
        .params(
            TODAY
                .minusMonths(1)
                .atStartOfDay()
                .atZone(java.time.ZoneId.systemDefault())
                .toOffsetDateTime(),
            deposit)
        .update();

    assertThat(dueOf(deposit)).hasSize(1);
    assertThat(deposits.view(deposit).rows().getFirst().state()).isEqualTo(State.BEFORE);
  }

  @Test
  void interestDoesNotCountAsGoalContribution() {
    Due due = dueOf(deposit).getLast();
    deposits.post(deposit, due.period().end(), money("90"), TODAY);

    assertThat(stats.monthlyNet(List.of(deposit), "BYN", YearMonth.now().minusMonths(1)))
        .doesNotContainKey(deposit);
  }

  @Test
  void yearReportSumsReceivedInterestAndScheduledRest() {
    Due due = dueOf(deposit).getLast();
    deposits.post(deposit, due.period().end(), money("90"), TODAY);

    Deposits.YearReport report = deposits.year(TODAY.getYear());

    assertThat(report.rows())
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.received()).isEqualByComparingTo("90");
              assertThat(r.months()[TODAY.getMonthValue() - 1]).isEqualByComparingTo("90");
            });
    assertThat(report.received()).isEqualByComparingTo("90");
  }

  @Test
  void effectiveRateAccountsForCapitalizationAndTax() {
    var monthly = accounts.deposit(deposit).orElseThrow();
    // 12 % ежемесячно без налога: (1 + 0,01)^12 − 1
    assertThat(Deposits.effectiveRate(monthly)).isEqualByComparingTo("12.68");
  }

  @Test
  void pagesRender() {
    LocalDate end = dueOf(deposit).getFirst().period().end();
    assertThat(mvc.get().uri("/deposits").with(user("owner")))
        .hasStatusOk()
        .bodyText()
        .contains("Годовой")
        .contains("Пора провести проценты");
    assertThat(mvc.get().uri("/deposits/" + deposit).with(user("owner")))
        .hasStatusOk()
        .bodyText()
        .contains("График начислений");
    assertThat(mvc.get().uri("/").with(user("owner")))
        .hasStatusOk()
        .bodyText()
        .contains("Проценты по вкладам");

    assertThat(
            mvc.post()
                .uri("/deposits/" + deposit + "/post")
                .with(user("owner"))
                .with(csrf())
                .param("periodEnd", end.toString())
                .param("amount", "101,92")
                .param("back", "/deposits/" + deposit))
        .hasStatus3xxRedirection()
        .hasRedirectedUrl("/deposits/" + deposit);
    assertThat(accounts.balanceOf(deposit)).isEqualByComparingTo("10101.92");

    assertThat(mvc.get().uri("/deposits/report").with(user("owner")))
        .hasStatusOk()
        .bodyText()
        .contains("Годовой");
    assertThat(mvc.get().uri("/deposits/report?year=2020").with(user("owner"))).hasStatusOk();
    assertThat(mvc.get().uri("/reports").with(user("owner")))
        .hasStatusOk()
        .bodyText()
        .contains("Капитал по месяцам");
    assertThat(mvc.get().uri("/accounts/" + deposit + "/edit").with(user("owner")))
        .hasStatusOk()
        .bodyText()
        .contains("Проценты выплачиваются на");
  }

  private UUID deposit(String name, String capitalization, @Nullable UUID payout) {
    UUID id =
        jdbc.sql(
                """
                insert into account (name, type, currency, opening_balance, opened_on, created_at)
                values (?, 'DEPOSIT', 'BYN', 10000, ?, ?) returning id
                """)
            .params(
                name,
                OPENED,
                OPENED.atStartOfDay().atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime())
            .query(UUID.class)
            .single();
    jdbc.sql(
            """
            insert into deposit_terms (account_id, bank, annual_rate, capitalization, payout_account_id,
                                       tax_rate, opened_on, matures_on)
            values (?, 'Банк', 12, ?, ?, 0, ?, ?)
            """)
        .params(id, capitalization, payout, OPENED, OPENED.plusYears(1))
        .update();
    return id;
  }

  private List<Due> dueOf(UUID accountId) {
    return deposits.due().stream()
        .filter(d -> d.deposit().account().id().equals(accountId))
        .toList();
  }

  private static BigDecimal money(String value) {
    return new BigDecimal(value);
  }
}
