package com.spendings.report;

import com.spendings.fx.BaseCurrency;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class DashboardController {

  private final BaseCurrency baseCurrency;
  private final JdbcClient jdbc;

  DashboardController(BaseCurrency baseCurrency, JdbcClient jdbc) {
    this.baseCurrency = baseCurrency;
    this.jdbc = jdbc;
  }

  @GetMapping("/")
  String dashboard(Model model) {
    model.addAttribute("baseCurrency", baseCurrency.code());
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
    return new SystemStatus(database, schema, Runtime.version().toString());
  }

  record SystemStatus(String database, @Nullable String schemaVersion, String java) {}
}
