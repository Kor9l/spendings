package com.spendings.report;

import com.spendings.account.Accounts;
import com.spendings.account.Accounts.AccountBalance;
import com.spendings.fx.BaseCurrency;
import com.spendings.operation.Feed;
import com.spendings.operation.OperationFeed;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class DashboardController {

  private static final int RECENT_DAYS = 3;

  private final BaseCurrency baseCurrency;
  private final Accounts accounts;
  private final OperationFeed operations;
  private final JdbcClient jdbc;

  DashboardController(
      BaseCurrency baseCurrency, Accounts accounts, OperationFeed operations, JdbcClient jdbc) {
    this.baseCurrency = baseCurrency;
    this.accounts = accounts;
    this.operations = operations;
    this.jdbc = jdbc;
  }

  @GetMapping("/")
  String dashboard(Model model) {
    List<AccountBalance> balances =
        accounts.balances().stream().filter(b -> !b.account().archived()).toList();
    model.addAttribute("baseCurrency", baseCurrency.code());
    model.addAttribute("balances", balances);
    model.addAttribute("totals", totalsByCurrency(balances));
    // итоги в базовой валюте появятся вместе с курсами (M2); пока — по валютам
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

  private static Map<String, BigDecimal> totalsByCurrency(List<AccountBalance> balances) {
    return balances.stream()
        .collect(
            Collectors.groupingBy(
                b -> b.account().currency(),
                TreeMap::new,
                Collectors.reducing(BigDecimal.ZERO, AccountBalance::balance, BigDecimal::add)));
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
    return new SystemStatus(database, schema, Runtime.version().toString());
  }

  record SystemStatus(String database, @Nullable String schemaVersion, String java) {}
}
