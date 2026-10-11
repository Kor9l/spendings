package com.spendings.report;

import com.spendings.account.Accounts;
import com.spendings.account.Accounts.AccountBalance;
import com.spendings.category.Categories;
import com.spendings.category.CategoryKind;
import com.spendings.category.CategoryRef;
import com.spendings.fx.ExchangeRates;
import com.spendings.report.ReportQueries.MonthTotals;
import com.spendings.report.ReportQueries.Totals;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Отчёты в базовой валюте: итоги месяца, разбивка по категориям, динамика, денежный поток. */
@Service
@Transactional(readOnly = true)
class Reports {

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private final ReportQueries queries;
  private final Categories categories;
  private final Accounts accounts;
  private final ExchangeRates rates;

  Reports(ReportQueries queries, Categories categories, Accounts accounts, ExchangeRates rates) {
    this.queries = queries;
    this.categories = categories;
    this.accounts = accounts;
    this.rates = rates;
  }

  Totals totals(LocalDate from, LocalDate to, String base) {
    return queries.totals(from, to, base);
  }

  /** Доля сбережений от дохода, в процентах; пусто, если доходов не было. */
  static Optional<BigDecimal> savingsRate(Totals totals) {
    if (totals.income().signum() <= 0) {
      return Optional.empty();
    }
    return Optional.of(
        totals.net().multiply(HUNDRED).divide(totals.income(), 1, RoundingMode.HALF_UP));
  }

  /** Изменение в процентах относительно прошлого периода; пусто, если сравнивать не с чем. */
  static Optional<BigDecimal> change(BigDecimal current, BigDecimal previous) {
    if (previous.signum() == 0) {
      return Optional.empty();
    }
    return Optional.of(
        current.subtract(previous).multiply(HUNDRED).divide(previous, 0, RoundingMode.HALF_UP));
  }

  /** Расходы или доходы по категориям верхнего уровня, с подкатегориями, по убыванию суммы. */
  Breakdown breakdown(CategoryKind kind, LocalDate from, LocalDate to, String base) {
    Map<UUID, BigDecimal> leaves = queries.byCategory(kind.name(), from, to, base);
    Map<UUID, CategoryRef> all = categories.all();

    Map<UUID, Map<UUID, BigDecimal>> byTop = new LinkedHashMap<>();
    for (var e : leaves.entrySet()) {
      CategoryRef c = all.get(e.getKey());
      if (c == null) {
        continue;
      }
      byTop
          .computeIfAbsent(c.topLevelId(), k -> new LinkedHashMap<>())
          .merge(c.id(), e.getValue(), BigDecimal::add);
    }

    BigDecimal total = leaves.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    List<TopRow> rows = new ArrayList<>();
    for (var e : byTop.entrySet()) {
      CategoryRef top = all.get(e.getKey());
      if (top == null) {
        continue;
      }
      BigDecimal topTotal = e.getValue().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal direct = e.getValue().getOrDefault(top.id(), BigDecimal.ZERO);
      List<ChildRow> children = new ArrayList<>();
      e.getValue()
          .forEach(
              (id, amount) -> {
                if (!id.equals(top.id())) {
                  children.add(new ChildRow(all.get(id), amount, share(amount, topTotal)));
                }
              });
      children.sort(Comparator.comparing(ChildRow::amount).reversed());
      rows.add(new TopRow(top, topTotal, share(topTotal, total), direct, children));
    }
    rows.sort(Comparator.comparing(TopRow::amount).reversed());
    return new Breakdown(kind, total, rows);
  }

  /**
   * Динамика за {@code months} месяцев, заканчивая {@code last}; по категории — с подкатегориями.
   */
  List<MonthTotals> trend(YearMonth last, int months, String base, @Nullable UUID categoryId) {
    List<UUID> ids = new ArrayList<>();
    if (categoryId != null) {
      for (CategoryRef c : categories.all().values()) {
        if (c.id().equals(categoryId) || categoryId.equals(c.parentId())) {
          ids.add(c.id());
        }
      }
    }
    return queries.monthly(last.minusMonths(months - 1L), last, base, ids);
  }

  /** Капитал: остатки счетов, которые учитываются в капитале, по сегодняшнему курсу. */
  NetWorth netWorth(String base) {
    BigDecimal total = BigDecimal.ZERO;
    Set<String> missing = new HashSet<>();
    LocalDate today = LocalDate.now();
    for (AccountBalance b : accounts.balances()) {
      if (b.account().archived()) {
        continue;
      }
      Optional<BigDecimal> converted =
          rates.convert(b.balance(), b.account().currency(), base, today);
      if (converted.isPresent()) {
        total = total.add(converted.get());
      } else {
        missing.add(b.account().currency());
      }
    }
    return new NetWorth(total.setScale(2, RoundingMode.HALF_EVEN), missing);
  }

  /** Данные для диаграммы Sankey: доходы → «Доходы» → расходы и сбережения. */
  Map<String, Object> cashFlow(Breakdown income, Breakdown expenses) {
    String hub = "Доходы";
    var nodes = new ArrayList<Map<String, Object>>();
    var links = new ArrayList<Map<String, Object>>();
    Set<String> names = new HashSet<>();
    names.add(hub);
    nodes.add(Map.of("name", hub, "color", "#5c7cfa"));

    Set<String> expenseNames = new HashSet<>();
    expenses.rows().forEach(r -> expenseNames.add(r.category().name()));
    for (TopRow r : income.rows()) {
      String name =
          expenseNames.contains(r.category().name())
              ? r.category().name() + " · доход"
              : r.category().name();
      names.add(name);
      nodes.add(Map.of("name", name, "color", r.category().color()));
      links.add(Map.of("source", name, "target", hub, "value", chartNumber(r.amount())));
    }
    for (TopRow r : expenses.rows()) {
      nodes.add(Map.of("name", r.category().name(), "color", r.category().color()));
      links.add(
          Map.of("source", hub, "target", r.category().name(), "value", chartNumber(r.amount())));
    }
    BigDecimal saved = income.total().subtract(expenses.total());
    if (saved.signum() > 0) {
      nodes.add(Map.of("name", "Сбережения", "color", "#2b8a3e"));
      links.add(Map.of("source", hub, "target", "Сбережения", "value", chartNumber(saved)));
    } else if (saved.signum() < 0) {
      nodes.add(Map.of("name", "Из накоплений", "color", "#e8590c"));
      links.add(
          Map.of("source", "Из накоплений", "target", hub, "value", chartNumber(saved.negate())));
    }
    return Map.of("type", "sankey", "nodes", nodes, "links", links);
  }

  /** Данные для sunburst: категория → подкатегории. */
  static Map<String, Object> sunburst(Breakdown breakdown) {
    var data = new ArrayList<Map<String, Object>>();
    for (TopRow row : breakdown.rows()) {
      var children = new ArrayList<Map<String, Object>>();
      for (ChildRow child : row.children()) {
        children.add(Map.of("name", child.category().name(), "value", chartNumber(child.amount())));
      }
      if (row.direct().signum() > 0 && !children.isEmpty()) {
        children.add(Map.of("name", "без уточнения", "value", chartNumber(row.direct())));
      }
      var node = new LinkedHashMap<String, Object>();
      node.put("name", row.category().name());
      node.put("itemStyle", Map.of("color", row.category().color()));
      if (children.isEmpty()) {
        node.put("value", chartNumber(row.amount()));
      } else {
        node.put("children", children);
      }
      data.add(node);
    }
    return Map.of("type", "sunburst", "data", data);
  }

  static Map<String, Object> trendChart(List<MonthTotals> months) {
    return Map.of(
        "type", "trend",
        "months", months.stream().map(m -> m.month().toString()).toList(),
        "income", months.stream().map(m -> chartNumber(m.income())).toList(),
        "expenses", months.stream().map(m -> chartNumber(m.expenses())).toList());
  }

  private static BigDecimal share(BigDecimal part, BigDecimal whole) {
    return whole.signum() == 0
        ? BigDecimal.ZERO
        : part.multiply(HUNDRED).divide(whole, 1, RoundingMode.HALF_UP);
  }

  private static double chartNumber(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_EVEN).doubleValue();
  }

  record Breakdown(CategoryKind kind, BigDecimal total, List<TopRow> rows) {

    public boolean isEmpty() {
      return rows.isEmpty();
    }
  }

  /**
   * @param direct сумма, отнесённая прямо к категории, без подкатегории
   */
  record TopRow(
      CategoryRef category,
      BigDecimal amount,
      BigDecimal share,
      BigDecimal direct,
      List<ChildRow> children) {}

  record ChildRow(CategoryRef category, BigDecimal amount, BigDecimal share) {}

  record NetWorth(BigDecimal total, Set<String> missingRates) {}
}
