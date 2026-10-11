package com.spendings.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import com.spendings.Fixtures;
import com.spendings.IntegrationTest;
import com.spendings.category.CategoryKind;
import com.spendings.operation.Feed;
import com.spendings.operation.OperationFeed;
import com.spendings.operation.Operations;
import com.spendings.operation.RecordOperation.RecordExpense;
import com.spendings.operation.RecordOperation.RecordIncome;
import com.spendings.operation.RecordOperation.RecordTransfer;
import com.spendings.operation.RecordOperation.Split;
import com.spendings.report.ReportQueries.MonthTotals;
import com.spendings.report.ReportQueries.Totals;
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

/** Отчёты в BYN сходятся с операциями, пересчитанными вручную по курсу на дату. */
@IntegrationTest
class ReportsTests {

  private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
  private static final LocalDate TO = LocalDate.of(2026, 9, 30);

  @Autowired Reports reports;
  @Autowired Operations operations;
  @Autowired OperationFeed feed;
  @Autowired Fixtures fixtures;
  @Autowired JdbcClient jdbc;
  @Autowired MockMvcTester mvc;

  UUID card;
  UUID dollars;
  UUID groceries;
  UUID cafe;
  UUID fuel;

  @BeforeEach
  void setUp() {
    card = fixtures.account("Карта", "BYN", "1000");
    dollars = fixtures.account("Доллары", "USD", "500");
    groceries = fixtures.category("Еда", "Продукты");
    cafe = fixtures.category("Еда", "Кафе и рестораны");
    fuel = fixtures.category("Транспорт", "Топливо");
    rate("2026-09-01", "3.00");
    rate("2026-09-15", "3.10");

    expense(card, groceries, "60.00", "2026-09-05");
    expense(card, cafe, "15.00", "2026-09-06");
    expense(dollars, fuel, "20.00", "2026-09-10"); // 20 USD × 3,00 = 60 BYN
    expense(dollars, fuel, "10.00", "2026-09-20"); // 10 USD × 3,10 = 31 BYN
    operations.record(
        new RecordIncome(
            card,
            LocalDate.parse("2026-09-05"),
            List.of(new Split(fixtures.incomeCategory("Зарплата"), new BigDecimal("1500"))),
            null,
            null));
    // перевод — не доход и не расход
    operations.record(
        new RecordTransfer(
            card,
            new BigDecimal("300"),
            dollars,
            new BigDecimal("97"),
            LocalDate.parse("2026-09-21"),
            null));
    // вне периода
    expense(card, groceries, "999", "2026-10-01");
  }

  @Test
  void totalsAreConvertedAtTheRateOfTheOperationDate() {
    Totals totals = reports.totals(FROM, TO, "BYN");

    assertThat(totals.income()).isEqualByComparingTo("1500");
    assertThat(totals.expenses()).isEqualByComparingTo("166"); // 60 + 15 + 60 + 31
    assertThat(totals.net()).isEqualByComparingTo("1334");
    assertThat(totals.missingRates()).isZero();
    assertThat(Reports.savingsRate(totals))
        .hasValueSatisfying(r -> assertThat(r).isEqualByComparingTo("88.9"));
  }

  @Test
  void breakdownMatchesTotalsAndRollsUpSubcategories() {
    Reports.Breakdown expenses = reports.breakdown(CategoryKind.EXPENSE, FROM, TO, "BYN");

    assertThat(expenses.total()).isEqualByComparingTo(reports.totals(FROM, TO, "BYN").expenses());
    assertThat(expenses.rows())
        .extracting(r -> r.category().name())
        .containsExactly("Транспорт", "Еда");
    Reports.TopRow food = expenses.rows().get(1);
    assertThat(food.amount()).isEqualByComparingTo("75");
    assertThat(food.children())
        .extracting(c -> c.category().name())
        .containsExactly("Продукты", "Кафе и рестораны");
    assertThat(food.children().getFirst().share()).isEqualByComparingTo("80.0");
  }

  @Test
  void reportMatchesTheFeedInTheSameCurrency() {
    // все расходы на еду — с BYN-карты, поэтому лента по категории и строка отчёта совпадают
    UUID food = fixtures.category("Еда");
    BigDecimal feedExpenses =
        feed.page(new Feed.Filter(FROM, TO, null, food, null, null), null).days().stream()
            .flatMap(d -> d.expenses().values().stream())
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .negate();
    BigDecimal reportFood =
        reports.breakdown(CategoryKind.EXPENSE, FROM, TO, "BYN").rows().stream()
            .filter(r -> r.category().id().equals(food))
            .findFirst()
            .orElseThrow()
            .amount();
    assertThat(feedExpenses).isEqualByComparingTo("75").isEqualByComparingTo(reportFood);
  }

  @Test
  void missingRateIsCountedNotTreatedAsOne() {
    UUID rubles = fixtures.account("Рубли", "RUB", "0");
    expense(rubles, groceries, "1000", "2026-09-12");

    Totals totals = reports.totals(FROM, TO, "BYN");
    assertThat(totals.missingRates()).isEqualTo(1);
    assertThat(totals.expenses()).isEqualByComparingTo("166");
  }

  @Test
  void trendFillsEmptyMonthsWithZeros() {
    List<MonthTotals> months =
        reports.trend(YearMonth.of(2026, 10), 3, "BYN", fixtures.category("Транспорт"));

    assertThat(months)
        .extracting(m -> m.month().toString())
        .containsExactly("2026-08", "2026-09", "2026-10");
    assertThat(months.get(0).expenses()).isEqualByComparingTo("0");
    assertThat(months.get(1).expenses()).isEqualByComparingTo("91");
    assertThat(months.get(1).income()).isEqualByComparingTo("0");
  }

  @Test
  void cashFlowEndsInSavings() {
    var income = reports.breakdown(CategoryKind.INCOME, FROM, TO, "BYN");
    var expenses = reports.breakdown(CategoryKind.EXPENSE, FROM, TO, "BYN");

    var sankey = reports.cashFlow(income, expenses);
    assertThat(sankey.get("links").toString())
        .contains("target=Сбережения")
        .contains("value=1334.0");
  }

  @Test
  void pagesAndExportRender() {
    assertThat(mvc.get().uri("/?month=2026-09").with(user("owner")))
        .hasStatusOk()
        .bodyText()
        .contains("сентябрь 2026")
        .contains("1 500,00 BYN");
    assertThat(mvc.get().uri("/reports?from=2026-09-01&to=2026-09-30").with(user("owner")))
        .hasStatusOk()
        .bodyText()
        .contains("Транспорт")
        .contains("data-chart");
    assertThat(
            mvc.get()
                .uri("/reports?from=2026-09-01&to=2026-09-30&kind=INCOME&trendCategoryId=" + fuel)
                .with(user("owner")))
        .hasStatusOk();
    assertThat(mvc.get().uri("/settings").with(user("owner")))
        .hasStatusOk()
        .bodyText()
        .contains("3,1000");

    assertThat(
            mvc.get()
                .uri("/operations/export.csv?from=2026-09-01&to=2026-09-30")
                .with(user("owner")))
        .hasStatusOk()
        .hasHeader("Content-Type", "text/csv; charset=UTF-8")
        .bodyText()
        .startsWith("﻿Дата;Тип;Счёт")
        .contains("2026-09-10;Расход;Доллары;USD;-20;Транспорт;Топливо")
        .doesNotContain("2026-10-01");
    assertThat(mvc.get().uri("/operations/export.xlsx").with(user("owner")))
        .hasStatusOk()
        .body()
        .satisfies(bytes -> assertThat(new String(bytes, 0, 2)).isEqualTo("PK"));
  }

  private void expense(UUID account, UUID category, String amount, String date) {
    operations.record(
        new RecordExpense(
            account,
            LocalDate.parse(date),
            List.of(new Split(category, new BigDecimal(amount))),
            null,
            null));
  }

  private void rate(String date, String value) {
    jdbc.sql(
            "insert into exchange_rate (rate_date, base, quote, rate, source) values (?, 'USD', 'BYN', ?, 'NBRB')")
        .params(LocalDate.parse(date), new BigDecimal(value))
        .update();
  }
}
