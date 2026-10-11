package com.spendings.planning;

import com.spendings.category.Categories;
import com.spendings.category.CategoryKind;
import com.spendings.category.CategoryRef;
import com.spendings.shared.DomainException;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/budgets")
class BudgetController {

  private final Budgets budgets;
  private final Categories categories;

  BudgetController(Budgets budgets, Categories categories) {
    this.budgets = budgets;
    this.categories = categories;
  }

  @GetMapping
  String budgets(
      @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM")
          @Nullable YearMonth month,
      Model model) {
    YearMonth shown = month == null ? budgets.current().month() : month;
    model.addAttribute("view", budgets.view(shown));
    model.addAttribute("isCurrent", shown.equals(budgets.current().month()));
    model.addAttribute("startDay", budgets.startDay());
    List<CategoryRef> options = new ArrayList<>();
    for (Categories.CategoryNode node : categories.tree(CategoryKind.EXPENSE, false)) {
      options.add(node.category());
      options.addAll(node.children());
    }
    model.addAttribute("options", options);
    return "planning/budgets";
  }

  @PostMapping
  String save(
      @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
      @RequestParam UUID categoryId,
      @RequestParam BigDecimal amount,
      @RequestParam(defaultValue = "false") boolean rollover,
      RedirectAttributes redirect) {
    try {
      budgets.save(categoryId, month, amount, rollover);
      redirect.addFlashAttribute(
          "toast", "Бюджет «" + categories.get(categoryId).displayName() + "» сохранён");
    } catch (DomainException e) {
      redirect.addFlashAttribute("toast", "Сумма бюджета не может быть отрицательной");
      redirect.addFlashAttribute("toastLevel", "danger");
    }
    return "redirect:/budgets?month=" + month;
  }

  @PostMapping("/{id}/delete")
  String delete(
      @PathVariable UUID id,
      @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
      RedirectAttributes redirect) {
    budgets.delete(id);
    redirect.addFlashAttribute("toast", "Бюджет удалён");
    return "redirect:/budgets?month=" + month;
  }

  @PostMapping("/copy")
  String copy(
      @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
      RedirectAttributes redirect) {
    int copied = budgets.copyFromPrevious(month);
    redirect.addFlashAttribute(
        "toast", copied == 0 ? "Копировать нечего" : "Скопировано бюджетов: " + copied);
    return "redirect:/budgets?month=" + month;
  }

  @PostMapping("/start-day")
  String startDay(@RequestParam int day, RedirectAttributes redirect) {
    try {
      budgets.changeStartDay(day);
      redirect.addFlashAttribute("toast", "Бюджетный месяц начинается " + day + "-го");
    } catch (DomainException e) {
      redirect.addFlashAttribute("toast", "День — от 1 до 28");
      redirect.addFlashAttribute("toastLevel", "danger");
    }
    return "redirect:/budgets";
  }
}
