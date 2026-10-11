package com.spendings.planning;

import com.spendings.account.Accounts;
import com.spendings.category.Categories;
import com.spendings.category.CategoryKind;
import com.spendings.category.CategoryRef;
import com.spendings.operation.OperationType;
import com.spendings.shared.DomainException;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** План: ожидаемые платежи, календарь месяца, прогноз остатка и регулярные операции. */
@Controller
@RequestMapping("/planning")
class PlanningController {

  static final int UPCOMING_DAYS = 30;

  private final Planner planner;
  private final Budgets budgets;
  private final Accounts accounts;
  private final Categories categories;

  PlanningController(Planner planner, Budgets budgets, Accounts accounts, Categories categories) {
    this.planner = planner;
    this.budgets = budgets;
    this.accounts = accounts;
    this.categories = categories;
  }

  @GetMapping
  String planning(
      @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM")
          @Nullable YearMonth month,
      Model model) {
    YearMonth shown = month == null ? YearMonth.now() : month;
    model.addAttribute("upcoming", planner.upcoming(UPCOMING_DAYS));
    model.addAttribute("forecast", planner.forecast(budgets.current().to()));
    model.addAttribute("month", shown);
    model.addAttribute("weeks", weeks(shown));
    model.addAttribute("calendar", planner.calendar(shown));
    model.addAttribute("today", LocalDate.now());
    model.addAttribute("rules", planner.rules());
    return "planning/index";
  }

  @PostMapping("/post")
  String post(
      @RequestParam UUID ruleId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueOn,
      @RequestParam BigDecimal amount,
      @RequestParam(required = false) @Nullable BigDecimal toAmount,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          @Nullable LocalDate date,
      @RequestParam(defaultValue = "/planning") String back,
      RedirectAttributes redirect) {
    try {
      planner.post(ruleId, dueOn, amount, toAmount, date == null ? dueOn : date);
      redirect.addFlashAttribute("toast", "Проведено");
    } catch (DomainException e) {
      redirect.addFlashAttribute(
          "toast", "Не удалось провести: проверьте сумму, счёт и категорию правила");
      redirect.addFlashAttribute("toastLevel", "danger");
    }
    return "redirect:" + safe(back);
  }

  @PostMapping("/skip")
  String skip(
      @RequestParam UUID ruleId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueOn,
      @RequestParam(defaultValue = "/planning") String back,
      RedirectAttributes redirect) {
    planner.skip(ruleId, dueOn);
    redirect.addFlashAttribute("toast", "Пропущено");
    return "redirect:" + safe(back);
  }

  @GetMapping("/rules/new")
  String newRule(@RequestParam(defaultValue = "EXPENSE") OperationType type, Model model) {
    var form = new RuleForm();
    form.setType(type == OperationType.ADJUSTMENT ? OperationType.EXPENSE : type);
    accounts.active().stream().findFirst().ifPresent(a -> form.setAccountId(a.id()));
    return form(model, form, null);
  }

  @PostMapping("/rules")
  String create(
      @Valid @ModelAttribute("form") RuleForm form,
      BindingResult errors,
      Model model,
      RedirectAttributes redirect) {
    if (errors.hasErrors()) {
      return form(model, form, null);
    }
    try {
      planner.create(form);
    } catch (DomainException e) {
      errors.reject(e.messageKey(), e.arguments(), null);
      return form(model, form, null);
    }
    redirect.addFlashAttribute(
        "toast", "Регулярная операция «" + form.getName().strip() + "» создана");
    return "redirect:/planning";
  }

  @GetMapping("/rules/{id}/edit")
  String edit(@PathVariable UUID id, Model model) {
    return form(model, planner.formFor(id), id);
  }

  @PostMapping("/rules/{id}")
  String update(
      @PathVariable UUID id,
      @Valid @ModelAttribute("form") RuleForm form,
      BindingResult errors,
      Model model,
      RedirectAttributes redirect) {
    if (errors.hasErrors()) {
      return form(model, form, id);
    }
    try {
      planner.update(id, form);
    } catch (DomainException e) {
      errors.reject(e.messageKey(), e.arguments(), null);
      return form(model, form, id);
    }
    redirect.addFlashAttribute("toast", "Сохранено");
    return "redirect:/planning";
  }

  @PostMapping("/rules/{id}/toggle")
  String toggle(@PathVariable UUID id, RedirectAttributes redirect) {
    planner.toggle(id);
    redirect.addFlashAttribute("toast", "Сохранено");
    return "redirect:/planning";
  }

  @PostMapping("/rules/{id}/delete")
  String delete(@PathVariable UUID id, RedirectAttributes redirect) {
    planner.delete(id);
    redirect.addFlashAttribute(
        "toast", "Регулярная операция удалена; проведённые операции остались");
    return "redirect:/planning";
  }

  private String form(Model model, RuleForm form, @Nullable UUID id) {
    model.addAttribute("form", form);
    model.addAttribute("ruleId", id);
    model.addAttribute("accounts", accounts.active());
    Map<CategoryKind, List<CategoryRef>> options = new LinkedHashMap<>();
    for (CategoryKind kind : CategoryKind.values()) {
      List<CategoryRef> flat = new ArrayList<>();
      for (Categories.CategoryNode node : categories.tree(kind, false)) {
        flat.add(node.category());
        flat.addAll(node.children());
      }
      options.put(kind, flat);
    }
    model.addAttribute("expenseCategories", options.get(CategoryKind.EXPENSE));
    model.addAttribute("incomeCategories", options.get(CategoryKind.INCOME));
    return "planning/rule-form";
  }

  /** Недели месяца для календаря: с понедельника, пустые клетки — {@code null}. */
  static List<List<@Nullable LocalDate>> weeks(YearMonth month) {
    List<List<@Nullable LocalDate>> weeks = new ArrayList<>();
    List<@Nullable LocalDate> week = new ArrayList<>();
    for (int i = 1; i < month.atDay(1).getDayOfWeek().getValue(); i++) {
      week.add(null);
    }
    for (int day = 1; day <= month.lengthOfMonth(); day++) {
      week.add(month.atDay(day));
      if (week.size() == 7) {
        weeks.add(week);
        week = new ArrayList<>();
      }
    }
    if (!week.isEmpty()) {
      while (week.size() < 7) {
        week.add(null);
      }
      weeks.add(week);
    }
    return weeks;
  }

  /** Возврат только на свои страницы. */
  private static String safe(String back) {
    return back.startsWith("/") && !back.startsWith("//") ? back : "/planning";
  }
}
