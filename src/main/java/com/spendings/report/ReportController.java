package com.spendings.report;

import com.spendings.category.Categories;
import com.spendings.category.CategoryKind;
import com.spendings.category.CategoryRef;
import com.spendings.fx.BaseCurrency;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import tools.jackson.databind.json.JsonMapper;

/** Отчёты: по категориям (sunburst), денежный поток (Sankey), динамика за 12 месяцев. */
@Controller
class ReportController {

  static final int TREND_MONTHS = 12;

  private final Reports reports;
  private final BaseCurrency baseCurrency;
  private final Categories categories;
  private final JsonMapper json;

  ReportController(
      Reports reports, BaseCurrency baseCurrency, Categories categories, JsonMapper json) {
    this.reports = reports;
    this.baseCurrency = baseCurrency;
    this.categories = categories;
    this.json = json;
  }

  @GetMapping("/reports")
  String reports(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          @Nullable LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          @Nullable LocalDate to,
      @RequestParam(defaultValue = "EXPENSE") CategoryKind kind,
      @RequestParam(required = false) @Nullable UUID trendCategoryId,
      Model model) {
    String base = baseCurrency.code();
    YearMonth thisMonth = YearMonth.now();
    LocalDate start = from == null ? thisMonth.atDay(1) : from;
    LocalDate end = to == null ? thisMonth.atEndOfMonth() : to;
    if (end.isBefore(start)) {
      end = start;
    }

    Reports.Breakdown expenses = reports.breakdown(CategoryKind.EXPENSE, start, end, base);
    Reports.Breakdown income = reports.breakdown(CategoryKind.INCOME, start, end, base);
    Reports.Breakdown shown = kind == CategoryKind.INCOME ? income : expenses;

    model.addAttribute("baseCurrency", base);
    model.addAttribute("from", start);
    model.addAttribute("to", end);
    model.addAttribute("kind", kind);
    model.addAttribute("totals", reports.totals(start, end, base));
    model.addAttribute("breakdown", shown);
    model.addAttribute("sunburst", json.writeValueAsString(Reports.sunburst(shown)));
    model.addAttribute("cashFlow", json.writeValueAsString(reports.cashFlow(income, expenses)));
    model.addAttribute("hasCashFlow", !income.isEmpty() || !expenses.isEmpty());

    var trend = reports.trend(YearMonth.from(end), TREND_MONTHS, base, trendCategoryId);
    model.addAttribute("trend", json.writeValueAsString(Reports.trendChart(trend)));
    model.addAttribute("trendCategoryId", trendCategoryId);
    model.addAttribute("trendCategories", topLevelOptions());
    model.addAttribute("periods", periods(thisMonth));
    return "report/reports";
  }

  private List<CategoryRef> topLevelOptions() {
    List<CategoryRef> options = new ArrayList<>();
    for (CategoryKind k : CategoryKind.values()) {
      for (Categories.CategoryNode node : categories.tree(k, false)) {
        options.add(node.category());
        options.addAll(node.children());
      }
    }
    return options;
  }

  /** Быстрый выбор периода: этот и прошлый месяц, с начала года, последние 12 месяцев. */
  private static List<Period> periods(YearMonth thisMonth) {
    YearMonth previous = thisMonth.minusMonths(1);
    return List.of(
        new Period("Этот месяц", thisMonth.atDay(1), thisMonth.atEndOfMonth()),
        new Period("Прошлый месяц", previous.atDay(1), previous.atEndOfMonth()),
        new Period("С начала года", thisMonth.withMonth(1).atDay(1), thisMonth.atEndOfMonth()),
        new Period("12 месяцев", thisMonth.minusMonths(11).atDay(1), thisMonth.atEndOfMonth()));
  }

  record Period(String label, LocalDate from, LocalDate to) {}
}
