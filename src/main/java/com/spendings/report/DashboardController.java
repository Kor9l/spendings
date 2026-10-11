package com.spendings.report;

import com.spendings.account.Accounts;
import com.spendings.category.CategoryKind;
import com.spendings.fx.BaseCurrency;
import com.spendings.goal.Goals;
import com.spendings.operation.Feed;
import com.spendings.operation.OperationFeed;
import com.spendings.planning.Budgets;
import com.spendings.planning.Planner;
import com.spendings.report.ReportQueries.Totals;
import java.time.LocalDate;
import java.time.YearMonth;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
class DashboardController {

  private static final int RECENT_DAYS = 3;
  private static final int TOP_CATEGORIES = 5;
  private static final int TOP_GOALS = 3;
  private static final int UPCOMING_DAYS = 7;

  private final BaseCurrency baseCurrency;
  private final Accounts accounts;
  private final OperationFeed operations;
  private final Reports reports;
  private final JdbcClient jdbc;
  private final Goals goals;
  private final Planner planner;
  private final Budgets budgets;

  DashboardController(
      BaseCurrency baseCurrency,
      Accounts accounts,
      OperationFeed operations,
      Reports reports,
      JdbcClient jdbc,
      Goals goals,
      Planner planner,
      Budgets budgets) {
    this.planner = planner;
    this.budgets = budgets;
    this.goals = goals;
    this.baseCurrency = baseCurrency;
    this.accounts = accounts;
    this.operations = operations;
    this.reports = reports;
    this.jdbc = jdbc;
  }

  @GetMapping("/")
  String dashboard(
      @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM")
          @Nullable YearMonth month,
      Model model) {
    String base = baseCurrency.code();
    YearMonth current = month == null ? YearMonth.now() : month;
    LocalDate from = current.atDay(1);
    LocalDate to = current.atEndOfMonth();

    Totals totals = reports.totals(from, to, base);
    YearMonth previous = current.minusMonths(1);
    Totals previousTotals = reports.totals(previous.atDay(1), previous.atEndOfMonth(), base);
    Reports.Breakdown expenses = reports.breakdown(CategoryKind.EXPENSE, from, to, base);

    model.addAttribute("baseCurrency", base);
    model.addAttribute("month", current);
    model.addAttribute("isCurrentMonth", current.equals(YearMonth.now()));
    model.addAttribute("from", from);
    model.addAttribute("to", to);
    model.addAttribute("totals", totals);
    model.addAttribute("savingsRate", Reports.savingsRate(totals).orElse(null));
    model.addAttribute(
        "expensesChange",
        Reports.change(totals.expenses(), previousTotals.expenses()).orElse(null));
    model.addAttribute("topExpenses", expenses.rows().stream().limit(TOP_CATEGORIES).toList());
    model.addAttribute("netWorth", reports.netWorth(base));
    model.addAttribute(
        "balances", accounts.balances().stream().filter(b -> !b.account().archived()).toList());

    model.addAttribute("goals", goals.active().stream().limit(TOP_GOALS).toList());
    model.addAttribute("upcoming", planner.upcoming(UPCOMING_DAYS));
    model.addAttribute("budgetWarnings", budgets.warnings());
    model.addAttribute("monthForecast", planner.forecast(budgets.current().to()));

    Feed recent = operations.page(Feed.Filter.none(), null, RECENT_DAYS);
    model.addAttribute("feed", new Feed(recent.days(), null));
    model.addAttribute("pageUrl", "/operations?");
    return "report/dashboard";
  }

  /** Фрагмент для htmx: что запущено и какая версия схемы применена. */
  @GetMapping("/system/status")
  String systemStatus(Model model) {
    model.addAttribute("status", loadStatus());
    return "report/dashboard :: system-status";
  }

  private SystemStatus loadStatus() {
    String database =
        jdbc.sql("select 'PostgreSQL ' || current_setting('server_version')")
            .query(String.class)
            .single();
    String schema =
        jdbc.sql(
                "select version from flyway_schema_history where success"
                    + " order by installed_rank desc limit 1")
            .query(String.class)
            .optional()
            .orElse(null);
    LocalDate lastRate =
        jdbc.sql("select max(rate_date) from exchange_rate")
            .query(LocalDate.class)
            .optional()
            .orElse(null);
    return new SystemStatus(database, schema, Runtime.version().toString(), lastRate);
  }

  record SystemStatus(
      String database, @Nullable String schemaVersion, String java, @Nullable LocalDate lastRate) {}
}
