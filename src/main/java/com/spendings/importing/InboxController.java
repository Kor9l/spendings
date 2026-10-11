package com.spendings.importing;

import com.spendings.account.AccountRef;
import com.spendings.account.Accounts;
import com.spendings.category.Categories;
import com.spendings.category.CategoryKind;
import com.spendings.category.CategoryRef;
import com.spendings.operation.OperationStats;
import com.spendings.operation.OperationStats.InboxItem;
import com.spendings.operation.OperationType;
import com.spendings.operation.Operations;
import com.spendings.shared.DomainException;
import com.spendings.shared.Htmx;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * «Входящие»: импортированные операции ждут проверки. Выбор категории с отметкой «запомнить»
 * создаёт правило и сразу применяет его к остальным операциям того же получателя.
 */
@Controller
@RequestMapping("/inbox")
class InboxController {

  private final OperationStats stats;
  private final Operations operations;
  private final Accounts accounts;
  private final Categories categories;
  private final CategorizationRules rules;

  InboxController(
      OperationStats stats,
      Operations operations,
      Accounts accounts,
      Categories categories,
      CategorizationRules rules) {
    this.stats = stats;
    this.operations = operations;
    this.accounts = accounts;
    this.categories = categories;
    this.rules = rules;
  }

  @GetMapping
  String inbox(Model model) {
    fill(model);
    return "importing/inbox";
  }

  @PostMapping("/{id}/category")
  String recategorize(
      @PathVariable UUID id,
      @RequestParam UUID categoryId,
      @RequestParam(defaultValue = "false") boolean remember,
      HttpServletRequest request,
      HttpServletResponse response,
      Model model) {
    InboxItem item = find(id);
    try {
      operations.recategorize(id, categoryId);
      if (remember && item.payee() != null && !item.payee().isBlank()) {
        CategoryKind kind =
            item.type() == OperationType.INCOME ? CategoryKind.INCOME : CategoryKind.EXPENSE;
        rules.learn(item.payee(), categoryId, kind);
        int applied = applyToSamePayee(item, categoryId);
        Htmx.toast(
            response,
            Htmx.ToastLevel.SUCCESS,
            "Правило сохранено" + (applied > 0 ? ", применено ещё к " + applied : ""));
      }
    } catch (DomainException e) {
      Htmx.toast(response, Htmx.ToastLevel.ERROR, "Категория не подходит к этой операции");
    }
    fill(model);
    return "importing/inbox :: list";
  }

  @PostMapping("/{id}/confirm")
  String confirm(@PathVariable UUID id, Model model) {
    operations.confirm(id);
    fill(model);
    return "importing/inbox :: list";
  }

  @PostMapping("/{id}/delete")
  String delete(@PathVariable UUID id, Model model) {
    operations.delete(id);
    fill(model);
    return "importing/inbox :: list";
  }

  @PostMapping("/batches/{batchId}/confirm")
  String confirmBatch(@PathVariable UUID batchId, RedirectAttributes redirect) {
    int confirmed = operations.confirmBatch(batchId);
    redirect.addFlashAttribute("toast", "Подтверждено операций: " + confirmed);
    return "redirect:/inbox";
  }

  private int applyToSamePayee(InboxItem changed, UUID categoryId) {
    int applied = 0;
    for (InboxItem other : stats.inbox()) {
      if (!other.id().equals(changed.id())
          && other.type() == changed.type()
          && other.lines() == 1
          && other.payee() != null
          && other.payee().equalsIgnoreCase(changed.payee())
          && !Objects.equals(other.categoryId(), categoryId)) {
        operations.recategorize(other.id(), categoryId);
        applied++;
      }
    }
    return applied;
  }

  private InboxItem find(UUID id) {
    return stats.inbox().stream()
        .filter(i -> i.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new com.spendings.shared.NotFoundException("Операция во «Входящих»"));
  }

  private void fill(Model model) {
    Map<UUID, AccountRef> accountsById =
        accounts.all().stream().collect(Collectors.toMap(AccountRef::id, a -> a));
    Map<UUID, CategoryRef> all = categories.all();
    Map<UUID, List<Row>> byBatch = new LinkedHashMap<>();
    for (InboxItem item : stats.inbox()) {
      AccountRef account = accountsById.get(item.accountId());
      byBatch
          .computeIfAbsent(
              item.batchId() == null ? new UUID(0, 0) : item.batchId(), k -> new ArrayList<>())
          .add(
              new Row(
                  item, account, item.categoryId() == null ? null : all.get(item.categoryId())));
    }
    model.addAttribute("groups", byBatch);
    model.addAttribute("expenseOptions", options(CategoryKind.EXPENSE));
    model.addAttribute("incomeOptions", options(CategoryKind.INCOME));
  }

  private List<CategoryRef> options(CategoryKind kind) {
    List<CategoryRef> flat = new ArrayList<>();
    for (Categories.CategoryNode node : categories.tree(kind, false)) {
      flat.add(node.category());
      flat.addAll(node.children());
    }
    return flat;
  }

  record Row(InboxItem item, @Nullable AccountRef account, @Nullable CategoryRef category) {

    public boolean uncategorized() {
      return category == null
          || ("Не разобрано".equals(category.name()) && category.parentId() == null);
    }
  }

  /** Сколько операций ждёт во «Входящих» — для значка в шапке на всех страницах. */
  @ControllerAdvice
  static class InboxCount {

    private final OperationStats stats;

    InboxCount(OperationStats stats) {
      this.stats = stats;
    }

    @ModelAttribute("inboxCount")
    int inboxCount(HttpServletRequest request) {
      return request.getUserPrincipal() == null ? 0 : stats.inboxCount();
    }
  }
}
