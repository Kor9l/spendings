package com.spendings.goal;

import com.spendings.account.Accounts;
import com.spendings.fx.BaseCurrency;
import com.spendings.fx.Currencies;
import com.spendings.shared.DomainException;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
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

@Controller
@RequestMapping("/goals")
class GoalController {

  private final Goals goals;
  private final Accounts accounts;
  private final Currencies currencies;
  private final BaseCurrency baseCurrency;

  GoalController(Goals goals, Accounts accounts, Currencies currencies, BaseCurrency baseCurrency) {
    this.goals = goals;
    this.accounts = accounts;
    this.currencies = currencies;
    this.baseCurrency = baseCurrency;
  }

  @GetMapping
  String list(Model model) {
    model.addAttribute("goals", goals.all());
    return "goal/list";
  }

  @GetMapping("/{id}")
  String details(@PathVariable UUID id, Model model) {
    Goals.GoalView goal = goals.get(id);
    model.addAttribute("goal", goal);
    model.addAttribute("contributions", goals.contributionsOf(id));
    model.addAttribute("today", LocalDate.now());
    return "goal/details";
  }

  /** «Если откладывать X в месяц» — фрагмент для htmx. */
  @GetMapping("/{id}/what-if")
  String whatIf(
      @PathVariable UUID id,
      @RequestParam(required = false) @Nullable BigDecimal monthly,
      Model model) {
    model.addAttribute("goal", goals.get(id));
    model.addAttribute("monthly", monthly);
    model.addAttribute(
        "whatIfEta", monthly == null || monthly.signum() <= 0 ? null : goals.whatIf(id, monthly));
    return "goal/details :: what-if";
  }

  @GetMapping("/new")
  String newForm(Model model) {
    return form(model, GoalForm.empty(accounts.active(), baseCurrency.code()), null);
  }

  @PostMapping
  String create(
      @Valid @ModelAttribute("form") GoalForm form,
      BindingResult errors,
      Model model,
      RedirectAttributes redirect) {
    if (errors.hasErrors()) {
      return form(model, form, null);
    }
    UUID id;
    try {
      id = goals.create(form);
    } catch (DomainException e) {
      errors.reject(e.messageKey(), e.arguments(), null);
      return form(model, form, null);
    }
    redirect.addFlashAttribute("toast", "Цель «" + form.getName().strip() + "» создана");
    return "redirect:/goals/" + id;
  }

  @GetMapping("/{id}/edit")
  String editForm(@PathVariable UUID id, Model model) {
    return form(model, goals.formFor(id), id);
  }

  @PostMapping("/{id}")
  String update(
      @PathVariable UUID id,
      @Valid @ModelAttribute("form") GoalForm form,
      BindingResult errors,
      Model model,
      RedirectAttributes redirect) {
    if (errors.hasErrors()) {
      return form(model, form, id);
    }
    try {
      goals.update(id, form);
    } catch (DomainException e) {
      errors.reject(e.messageKey(), e.arguments(), null);
      return form(model, form, id);
    }
    redirect.addFlashAttribute("toast", "Цель сохранена");
    return "redirect:/goals/" + id;
  }

  @PostMapping("/{id}/achieve")
  String achieve(@PathVariable UUID id, RedirectAttributes redirect) {
    goals.achieve(id);
    redirect.addFlashAttribute("toast", "Поздравляем: цель достигнута 🎉");
    return "redirect:/goals/" + id;
  }

  @PostMapping("/{id}/archive")
  String archive(@PathVariable UUID id, RedirectAttributes redirect) {
    goals.archive(id);
    redirect.addFlashAttribute("toast", "Цель перенесена в архив");
    return "redirect:/goals";
  }

  @PostMapping("/{id}/restore")
  String restore(@PathVariable UUID id, RedirectAttributes redirect) {
    goals.restore(id);
    redirect.addFlashAttribute("toast", "Цель снова активна");
    return "redirect:/goals/" + id;
  }

  @PostMapping("/{id}/delete")
  String delete(@PathVariable UUID id, RedirectAttributes redirect) {
    goals.delete(id);
    redirect.addFlashAttribute("toast", "Цель удалена");
    return "redirect:/goals";
  }

  @PostMapping("/{id}/contributions")
  String contribute(
      @PathVariable UUID id,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @RequestParam BigDecimal amount,
      @RequestParam(defaultValue = "SAVE") String direction,
      @RequestParam(required = false) @Nullable String note,
      RedirectAttributes redirect) {
    BigDecimal signed = "TAKE".equals(direction) ? amount.abs().negate() : amount.abs();
    try {
      goals.contribute(id, date, signed, note);
      redirect.addFlashAttribute("toast", signed.signum() > 0 ? "Отложено" : "Взято из резерва");
    } catch (DomainException e) {
      redirect.addFlashAttribute(
          "toast", "Сумма должна быть больше нуля, не больше двух знаков после запятой");
      redirect.addFlashAttribute("toastLevel", "danger");
    }
    return "redirect:/goals/" + id;
  }

  @PostMapping("/{id}/contributions/{contributionId}/delete")
  String deleteContribution(
      @PathVariable UUID id, @PathVariable UUID contributionId, RedirectAttributes redirect) {
    goals.deleteContribution(id, contributionId);
    redirect.addFlashAttribute("toast", "Отметка удалена");
    return "redirect:/goals/" + id;
  }

  private String form(Model model, GoalForm form, @Nullable UUID id) {
    form.relabel(accounts.active());
    model.addAttribute("form", form);
    model.addAttribute("goalId", id);
    model.addAttribute("currencies", currencies.all());
    model.addAttribute("thisMonth", YearMonth.now());
    return "goal/form";
  }
}
