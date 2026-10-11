package com.spendings.account;

import com.spendings.account.Accounts.AccountBalance;
import com.spendings.fx.Currencies;
import com.spendings.shared.DomainException;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/accounts")
class AccountController {

  private final Accounts accounts;
  private final Currencies currencies;

  AccountController(Accounts accounts, Currencies currencies) {
    this.accounts = accounts;
    this.currencies = currencies;
  }

  @GetMapping
  String list(Model model) {
    List<AccountBalance> balances = accounts.balances();
    model.addAttribute("active", balances.stream().filter(b -> !b.account().archived()).toList());
    model.addAttribute("archived", balances.stream().filter(b -> b.account().archived()).toList());
    model.addAttribute("totals", totalsByCurrency(balances));
    return "account/list";
  }

  @GetMapping("/new")
  String newForm(Model model) {
    return form(model, new AccountForm(), null);
  }

  @PostMapping
  String create(
      @Valid @ModelAttribute("form") AccountForm form,
      BindingResult errors,
      Model model,
      RedirectAttributes redirect) {
    if (errors.hasErrors()) {
      return form(model, form, null);
    }
    try {
      accounts.create(form);
    } catch (DomainException e) {
      errors.reject(e.messageKey(), e.arguments(), null);
      return form(model, form, null);
    }
    redirect.addFlashAttribute("toast", "Счёт «" + form.getName().strip() + "» создан");
    return "redirect:/accounts";
  }

  @GetMapping("/{id}/edit")
  String editForm(@PathVariable UUID id, Model model) {
    return form(model, accounts.formFor(id), id);
  }

  @PostMapping("/{id}")
  String update(
      @PathVariable UUID id,
      @Valid @ModelAttribute("form") AccountForm form,
      BindingResult errors,
      Model model,
      RedirectAttributes redirect) {
    if (errors.hasErrors()) {
      return form(model, form, id);
    }
    try {
      accounts.update(id, form);
    } catch (DomainException e) {
      errors.reject(e.messageKey(), e.arguments(), null);
      return form(model, form, id);
    }
    redirect.addFlashAttribute("toast", "Счёт сохранён");
    return "redirect:/accounts";
  }

  @PostMapping("/{id}/archive")
  String archive(@PathVariable UUID id, RedirectAttributes redirect) {
    accounts.archive(id);
    redirect.addFlashAttribute("toast", "Счёт перенесён в архив");
    return "redirect:/accounts";
  }

  @PostMapping("/{id}/restore")
  String restore(@PathVariable UUID id, RedirectAttributes redirect) {
    accounts.restore(id);
    redirect.addFlashAttribute("toast", "Счёт возвращён из архива");
    return "redirect:/accounts";
  }

  @PostMapping("/{id}/delete")
  String delete(@PathVariable UUID id, RedirectAttributes redirect) {
    try {
      accounts.delete(id);
      redirect.addFlashAttribute("toast", "Счёт удалён");
    } catch (DomainException e) {
      redirect.addFlashAttribute("toast", "У счёта есть операции: его можно только архивировать");
      redirect.addFlashAttribute("toastLevel", "danger");
    }
    return "redirect:/accounts";
  }

  /** Порядок после перетаскивания в списке (SortableJS). */
  @PostMapping("/reorder")
  ResponseEntity<Void> reorder(@RequestParam("id") List<UUID> ids) {
    accounts.reorder(ids);
    return ResponseEntity.noContent().build();
  }

  private String form(Model model, AccountForm form, @Nullable UUID id) {
    model.addAttribute("form", form);
    model.addAttribute("accountId", id);
    model.addAttribute("types", AccountType.values());
    model.addAttribute("currencies", currencies.all());
    model.addAttribute(
        "payoutAccounts", accounts.active().stream().filter(a -> !a.id().equals(id)).toList());
    return "account/form";
  }

  private static Map<String, BigDecimal> totalsByCurrency(List<AccountBalance> balances) {
    return balances.stream()
        .filter(b -> !b.account().archived())
        .collect(
            Collectors.groupingBy(
                b -> b.account().currency(),
                TreeMap::new,
                Collectors.reducing(BigDecimal.ZERO, AccountBalance::balance, BigDecimal::add)));
  }
}
