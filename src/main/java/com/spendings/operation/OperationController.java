package com.spendings.operation;

import com.spendings.account.AccountRef;
import com.spendings.account.Accounts;
import com.spendings.category.Categories;
import com.spendings.category.Categories.CategoryNode;
import com.spendings.category.CategoryKind;
import com.spendings.category.CategoryRef;
import com.spendings.fx.Currencies;
import com.spendings.operation.RecordOperation.AdjustBalance;
import com.spendings.shared.Amounts;
import com.spendings.shared.DomainException;
import com.spendings.shared.Htmx;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tools.jackson.databind.json.JsonMapper;

@Controller
class OperationController {

  private final Operations operations;
  private final OperationFeed feed;
  private final Accounts accounts;
  private final Categories categories;
  private final Currencies currencies;
  private final JsonMapper json;
  private final OperationExport export;

  OperationController(
      Operations operations,
      OperationFeed feed,
      Accounts accounts,
      Categories categories,
      Currencies currencies,
      JsonMapper json,
      OperationExport export) {
    this.export = export;
    this.operations = operations;
    this.feed = feed;
    this.accounts = accounts;
    this.categories = categories;
    this.currencies = currencies;
    this.json = json;
  }

  // ---- лента ----

  @GetMapping("/operations")
  String list(
      @ModelAttribute("filter") FilterForm filter,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          @Nullable LocalDate before,
      HttpServletRequest request,
      Model model) {
    model.addAttribute("feed", feed.page(filter.toFilter(), before));
    model.addAttribute("pageUrl", "/operations?" + queryWithoutBefore(request));
    model.addAttribute("exportQuery", queryWithoutBefore(request));
    switch (htmxPart(request)) {
      case NEXT_PAGE -> {
        return "operation/feed :: days";
      }
      case RESULTS -> {
        return "operation/feed :: results";
      }
      case FULL_PAGE -> {
        model.addAttribute("accounts", accounts.all());
        model.addAttribute("categoryOptions", categoryOptions());
        model.addAttribute("types", OperationType.values());
        return "operation/feed";
      }
    }
    throw new IllegalStateException();
  }

  /**
   * Какую часть ленты просит htmx: смена фильтров меняет блок {@code #results}, бесконечная
   * прокрутка — подгружает дни, а навигация через {@code hx-boost} ждёт страницу целиком.
   */
  private static Part htmxPart(HttpServletRequest request) {
    if (!Htmx.isHtmxRequest(request) || "true".equals(request.getHeader("HX-Boosted"))) {
      return Part.FULL_PAGE;
    }
    return "results".equals(request.getHeader("HX-Target")) ? Part.RESULTS : Part.NEXT_PAGE;
  }

  private enum Part {
    FULL_PAGE,
    RESULTS,
    NEXT_PAGE
  }

  private static String queryWithoutBefore(HttpServletRequest request) {
    String query = request.getQueryString();
    if (query == null) {
      return "";
    }
    return java.util.Arrays.stream(query.split("&"))
        .filter(p -> !p.startsWith("before=") && !p.isEmpty())
        .collect(Collectors.joining("&"));
  }

  /** Выписка счёта: та же лента, но с остатком после каждой операции. */
  @GetMapping("/accounts/{id}")
  String statement(
      @PathVariable UUID id,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          @Nullable LocalDate before,
      HttpServletRequest request,
      Model model) {
    AccountRef account = accounts.get(id);
    model.addAttribute("account", account);
    model.addAttribute("feed", feed.page(Feed.Filter.none().forAccount(id), before));
    model.addAttribute("pageUrl", "/accounts/" + id + "?");
    if (htmxPart(request) == Part.NEXT_PAGE) {
      return "operation/feed :: days";
    }
    model.addAttribute("balance", accounts.balanceOf(id));
    return "operation/statement";
  }

  /** Сверка: пользователь вводит фактический остаток, разница становится корректировкой. */
  @PostMapping("/accounts/{id}/reconcile")
  String reconcile(
      @PathVariable UUID id, @RequestParam BigDecimal actualBalance, RedirectAttributes redirect) {
    AccountRef account = accounts.get(id);
    BigDecimal delta = actualBalance.subtract(accounts.balanceOf(id));
    if (delta.signum() == 0) {
      redirect.addFlashAttribute("toast", "Остаток совпадает, корректировка не нужна");
    } else {
      try {
        operations.record(new AdjustBalance(id, LocalDate.now(), delta, "Сверка остатка"));
        redirect.addFlashAttribute(
            "toast",
            "Остаток исправлен на " + Amounts.formatSigned(delta) + " " + account.currency());
      } catch (DomainException e) {
        redirect.addFlashAttribute("toast", "Не удалось исправить остаток");
        redirect.addFlashAttribute("toastLevel", "danger");
      }
    }
    return "redirect:/accounts/" + id;
  }

  // ---- ввод и правка ----

  @GetMapping("/quick")
  String quick(
      @RequestParam(defaultValue = "EXPENSE") OperationType type,
      @RequestParam(required = false) @Nullable UUID accountId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          @Nullable LocalDate date,
      Model model) {
    var form = new OperationForm();
    form.setType(type == OperationType.ADJUSTMENT ? OperationType.EXPENSE : type);
    form.setAccountId(defaultAccount(accountId));
    if (date != null) {
      form.setDate(date);
    }
    return form(model, form, null);
  }

  @PostMapping("/operations")
  String create(
      @ModelAttribute("form") OperationForm form,
      BindingResult errors,
      @RequestParam(required = false) @Nullable String more,
      Model model,
      RedirectAttributes redirect) {
    Optional<RecordOperation> command = form.toCommand(errors);
    if (command.isEmpty()) {
      return form(model, form, null);
    }
    try {
      operations.record(command.get());
    } catch (DomainException e) {
      errors.reject(e.messageKey(), e.arguments(), null);
      return form(model, form, null);
    }
    redirect.addFlashAttribute("toast", savedMessage(form));
    if (more != null) {
      return "redirect:/quick?type="
          + form.getType()
          + "&accountId="
          + form.getAccountId()
          + "&date="
          + form.getDate();
    }
    return "redirect:/operations";
  }

  @GetMapping("/operations/{id}/edit")
  String editForm(@PathVariable UUID id, Model model) {
    return form(model, OperationForm.of(operations.find(id)), id);
  }

  @PostMapping("/operations/{id}")
  String update(
      @PathVariable UUID id,
      @ModelAttribute("form") OperationForm form,
      BindingResult errors,
      Model model,
      RedirectAttributes redirect) {
    Optional<RecordOperation> command = form.toCommand(errors);
    if (command.isEmpty()) {
      return form(model, form, id);
    }
    try {
      operations.update(id, form.getVersion(), command.get());
    } catch (DomainException e) {
      errors.reject(e.messageKey(), e.arguments(), null);
      return form(model, form, id);
    }
    redirect.addFlashAttribute("toast", "Операция сохранена");
    return "redirect:/operations";
  }

  @PostMapping("/operations/{id}/delete")
  String delete(@PathVariable UUID id, RedirectAttributes redirect) {
    operations.delete(id);
    redirect.addFlashAttribute("toast", "Операция удалена");
    return "redirect:/operations";
  }

  // ---- выгрузка ----

  @GetMapping("/operations/export.csv")
  void exportCsv(@ModelAttribute("filter") FilterForm filter, HttpServletResponse response)
      throws java.io.IOException {
    attachment(response, "text/csv; charset=UTF-8", "csv");
    export.csv(feed.export(filter.toFilter()), response.getOutputStream());
  }

  @GetMapping("/operations/export.xlsx")
  void exportXlsx(@ModelAttribute("filter") FilterForm filter, HttpServletResponse response)
      throws java.io.IOException {
    attachment(
        response, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx");
    export.xlsx(feed.export(filter.toFilter()), response.getOutputStream());
  }

  private static void attachment(
      HttpServletResponse response, String contentType, String extension) {
    response.setContentType(contentType);
    response.setHeader(
        "Content-Disposition",
        "attachment; filename=\"spendings-" + LocalDate.now() + "." + extension + "\"");
  }

  // ---- подсказки для ввода ----

  @GetMapping("/operations/payees")
  String payees(@RequestParam(defaultValue = "") String payee, Model model) {
    model.addAttribute("payees", feed.payees(payee));
    return "operation/form :: payee-options";
  }

  @GetMapping("/operations/payee-category")
  @ResponseBody
  ResponseEntity<Map<String, String>> payeeCategory(
      @RequestParam String payee, @RequestParam(defaultValue = "EXPENSE") OperationType type) {
    return feed.lastCategoryFor(payee, type)
        .map(id -> ResponseEntity.ok(Map.of("categoryId", id.toString())))
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  // ---- модель формы ----

  private String form(Model model, OperationForm form, @Nullable UUID id) {
    List<AccountRef> accountChoices = new ArrayList<>(accounts.active());
    // в правке старой операции архивный счёт остаётся в списке
    for (UUID used : new UUID[] {form.getAccountId(), form.getToAccountId()}) {
      if (used != null && accountChoices.stream().noneMatch(a -> a.id().equals(used))) {
        accounts.find(used).ifPresent(accountChoices::add);
      }
    }

    model.addAttribute("form", form);
    model.addAttribute("operationId", id);
    model.addAttribute("accounts", accountChoices);
    model.addAttribute("currencies", currencies.all());
    model.addAttribute("expenseTiles", tiles(CategoryKind.EXPENSE));
    model.addAttribute("incomeTiles", tiles(CategoryKind.INCOME));
    model.addAttribute("categoryOptions", categoryOptions());
    model.addAttribute("state", json.writeValueAsString(state(form, accountChoices)));
    return "operation/form";
  }

  /** Плитки категорий: часто используемые первыми, дальше — в порядке пользователя. */
  private List<CategoryNode> tiles(CategoryKind kind) {
    List<UUID> frequent = feed.frequentTopLevel(kind);
    return categories.tree(kind, false).stream()
        .sorted(
            Comparator.comparingInt(
                n -> {
                  int index = frequent.indexOf(n.category().id());
                  return index < 0 ? Integer.MAX_VALUE : index;
                }))
        .toList();
  }

  /** Для выпадающих списков: «Еда», «Еда › Кафе», … по видам. */
  private Map<CategoryKind, List<CategoryRef>> categoryOptions() {
    Map<CategoryKind, List<CategoryRef>> options = new LinkedHashMap<>();
    for (CategoryKind kind : CategoryKind.values()) {
      List<CategoryRef> flat = new ArrayList<>();
      for (CategoryNode node : categories.tree(kind, false)) {
        flat.add(node.category());
        flat.addAll(node.children());
      }
      options.put(kind, flat);
    }
    return options;
  }

  private Map<String, Object> state(OperationForm form, List<AccountRef> accountChoices) {
    Map<UUID, CategoryRef> all = categories.all();
    CategoryRef selected = form.getCategoryId() == null ? null : all.get(form.getCategoryId());
    var state = new LinkedHashMap<String, Object>();
    state.put("type", form.getType().name());
    state.put("cat", selected == null ? "" : selected.id().toString());
    state.put("top", selected == null ? "" : selected.topLevelId().toString());
    state.put("split", form.isSplit());
    state.put(
        "splits",
        form.getSplits().stream()
            .map(
                r ->
                    Map.of(
                        "categoryId", r.getCategoryId() == null ? "" : r.getCategoryId().toString(),
                        "amount", Amounts.forInput(r.getAmount()),
                        "note", r.getNote() == null ? "" : r.getNote()))
            .toList());
    state.put("accountId", form.getAccountId() == null ? "" : form.getAccountId().toString());
    state.put("toAccountId", form.getToAccountId() == null ? "" : form.getToAccountId().toString());
    state.put(
        "currencies",
        accountChoices.stream()
            .collect(Collectors.toMap(a -> a.id().toString(), AccountRef::currency)));
    state.put("showOriginal", form.getOriginalAmount() != null);
    return state;
  }

  private @Nullable UUID defaultAccount(@Nullable UUID requested) {
    List<AccountRef> active = accounts.active();
    if (requested != null && active.stream().anyMatch(a -> a.id().equals(requested))) {
      return requested;
    }
    return feed.lastUsedAccount()
        .filter(id -> active.stream().anyMatch(a -> a.id().equals(id)))
        .or(() -> active.stream().findFirst().map(AccountRef::id))
        .orElse(null);
  }

  private String savedMessage(OperationForm form) {
    String currency =
        form.getAccountId() == null
            ? ""
            : accounts.find(form.getAccountId()).map(AccountRef::currency).orElse("");
    BigDecimal amount = form.isSplit() ? form.getSplitTotal() : form.getAmount();
    String what =
        switch (form.getType()) {
          case EXPENSE -> "Расход";
          case INCOME -> "Доход";
          case TRANSFER -> "Перевод";
          case ADJUSTMENT -> "Корректировка";
        };
    return what + " " + Amounts.format(amount) + " " + currency + " сохранён";
  }

  /** Фильтры ленты из строки запроса. */
  public static class FilterForm {

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private @Nullable LocalDate from;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private @Nullable LocalDate to;

    private @Nullable UUID accountId;
    private @Nullable UUID categoryId;
    private @Nullable OperationType type;
    private @Nullable String q;

    Feed.Filter toFilter() {
      return new Feed.Filter(from, to, accountId, categoryId, type, q);
    }

    public boolean isActive() {
      return from != null
          || to != null
          || accountId != null
          || categoryId != null
          || type != null
          || (q != null && !q.isBlank());
    }

    public @Nullable LocalDate getFrom() {
      return from;
    }

    public void setFrom(@Nullable LocalDate from) {
      this.from = from;
    }

    public @Nullable LocalDate getTo() {
      return to;
    }

    public void setTo(@Nullable LocalDate to) {
      this.to = to;
    }

    public @Nullable UUID getAccountId() {
      return accountId;
    }

    public void setAccountId(@Nullable UUID accountId) {
      this.accountId = accountId;
    }

    public @Nullable UUID getCategoryId() {
      return categoryId;
    }

    public void setCategoryId(@Nullable UUID categoryId) {
      this.categoryId = categoryId;
    }

    public @Nullable OperationType getType() {
      return type;
    }

    public void setType(@Nullable OperationType type) {
      this.type = type;
    }

    public @Nullable String getQ() {
      return q;
    }

    public void setQ(@Nullable String q) {
      this.q = q;
    }
  }
}
