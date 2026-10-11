package com.spendings.operation;

import com.spendings.operation.RecordOperation.AdjustBalance;
import com.spendings.operation.RecordOperation.RecordExpense;
import com.spendings.operation.RecordOperation.RecordIncome;
import com.spendings.operation.RecordOperation.RecordTransfer;
import com.spendings.operation.RecordOperation.Split;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.Errors;

/**
 * Одна форма на все типы операций. Какие поля обязательны, зависит от типа — это проверяет {@link
 * #toCommand(Errors)}, а правила предметной области — {@link Operations}.
 */
public class OperationForm {

  private OperationType type = OperationType.EXPENSE;

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
  private LocalDate date = LocalDate.now();

  private @Nullable UUID accountId;
  private @Nullable BigDecimal amount;
  private @Nullable UUID categoryId;
  private @Nullable String payee;
  private @Nullable String note;
  private @Nullable BigDecimal originalAmount;
  private @Nullable String originalCurrency;

  private boolean split;
  private List<SplitRow> splits = new ArrayList<>();

  private @Nullable UUID toAccountId;
  private @Nullable BigDecimal toAmount;

  private int version;

  static OperationForm of(Operations.Recorded recorded) {
    var form = new OperationForm();
    form.version = recorded.version();
    form.date = recorded.date();
    switch (recorded.command()) {
      case RecordExpense e ->
          form.fillCategorized(
              OperationType.EXPENSE, e.accountId(), e.splits(), e.payee(), e.note());
      case RecordIncome i ->
          form.fillCategorized(
              OperationType.INCOME, i.accountId(), i.splits(), i.payee(), i.note());
      case RecordTransfer t -> {
        form.type = OperationType.TRANSFER;
        form.accountId = t.fromAccountId();
        form.amount = t.fromAmount();
        form.toAccountId = t.toAccountId();
        form.toAmount = t.toAmount();
        form.note = t.note();
      }
      case AdjustBalance a -> {
        form.type = OperationType.ADJUSTMENT;
        form.accountId = a.accountId();
        form.amount = a.delta();
        form.note = a.note();
      }
    }
    return form;
  }

  private void fillCategorized(
      OperationType type,
      UUID accountId,
      List<Split> splits,
      @Nullable String payee,
      @Nullable String note) {
    this.type = type;
    this.accountId = accountId;
    this.payee = payee;
    this.note = note;
    if (splits.size() == 1) {
      Split only = splits.getFirst();
      this.categoryId = only.categoryId();
      this.amount = only.amount();
      this.originalAmount = only.originalAmount();
      this.originalCurrency = only.originalCurrency();
    } else {
      this.split = true;
      for (Split s : splits) {
        var row = new SplitRow();
        row.setCategoryId(s.categoryId());
        row.setAmount(s.amount());
        row.setNote(s.note());
        this.splits.add(row);
      }
    }
  }

  /** Команда из формы или пусто, если не хватает обязательных полей (ошибки — в {@code errors}). */
  Optional<RecordOperation> toCommand(Errors errors) {
    if (date == null) {
      errors.rejectValue("date", "operation.error.dateRequired");
    }
    if (accountId == null) {
      errors.rejectValue("accountId", "operation.error.accountRequired");
    }
    return switch (type) {
      case EXPENSE, INCOME -> categorized(errors);
      case TRANSFER -> transfer(errors);
      case ADJUSTMENT -> {
        if (amount == null) {
          errors.rejectValue("amount", "operation.error.amountRequired");
        }
        yield errors.hasErrors()
            ? Optional.empty()
            : Optional.of(new AdjustBalance(accountId, date, amount, note));
      }
    };
  }

  private Optional<RecordOperation> categorized(Errors errors) {
    List<Split> parts = new ArrayList<>();
    if (split) {
      for (SplitRow row : splits) {
        if (row.getAmount() == null && row.getCategoryId() == null) {
          continue; // пустая строка разбивки
        }
        if (row.getAmount() == null || row.getCategoryId() == null) {
          errors.reject("operation.error.splitIncomplete");
          continue;
        }
        parts.add(new Split(row.getCategoryId(), row.getAmount(), null, null, row.getNote()));
      }
      if (parts.size() < 2 && !errors.hasErrors()) {
        errors.reject("operation.error.splitTooShort");
      }
    } else {
      if (amount == null) {
        errors.rejectValue("amount", "operation.error.amountRequired");
      }
      if (categoryId == null) {
        errors.rejectValue("categoryId", "operation.error.categoryRequired");
      }
      if (amount != null && categoryId != null) {
        BigDecimal original = originalAmount;
        parts.add(
            new Split(
                categoryId, amount, original, original == null ? null : originalCurrency, null));
      }
    }
    if (errors.hasErrors()) {
      return Optional.empty();
    }
    return Optional.of(
        type == OperationType.EXPENSE
            ? new RecordExpense(accountId, date, parts, payee, note)
            : new RecordIncome(accountId, date, parts, payee, note));
  }

  private Optional<RecordOperation> transfer(Errors errors) {
    if (amount == null) {
      errors.rejectValue("amount", "operation.error.amountRequired");
    }
    if (toAccountId == null) {
      errors.rejectValue("toAccountId", "operation.error.toAccountRequired");
    }
    if (errors.hasErrors()) {
      return Optional.empty();
    }
    BigDecimal received = toAmount == null ? amount : toAmount;
    return Optional.of(new RecordTransfer(accountId, amount, toAccountId, received, date, note));
  }

  /** Сумма всех строк разбивки — для показа итога. */
  public BigDecimal getSplitTotal() {
    return splits.stream()
        .map(SplitRow::getAmount)
        .filter(a -> a != null)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  public OperationType getType() {
    return type;
  }

  public void setType(OperationType type) {
    this.type = type;
  }

  public LocalDate getDate() {
    return date;
  }

  public void setDate(LocalDate date) {
    this.date = date;
  }

  public @Nullable UUID getAccountId() {
    return accountId;
  }

  public void setAccountId(@Nullable UUID accountId) {
    this.accountId = accountId;
  }

  public @Nullable BigDecimal getAmount() {
    return amount;
  }

  public void setAmount(@Nullable BigDecimal amount) {
    this.amount = amount;
  }

  public @Nullable UUID getCategoryId() {
    return categoryId;
  }

  public void setCategoryId(@Nullable UUID categoryId) {
    this.categoryId = categoryId;
  }

  public @Nullable String getPayee() {
    return payee;
  }

  public void setPayee(@Nullable String payee) {
    this.payee = payee;
  }

  public @Nullable String getNote() {
    return note;
  }

  public void setNote(@Nullable String note) {
    this.note = note;
  }

  public @Nullable BigDecimal getOriginalAmount() {
    return originalAmount;
  }

  public void setOriginalAmount(@Nullable BigDecimal originalAmount) {
    this.originalAmount = originalAmount;
  }

  public @Nullable String getOriginalCurrency() {
    return originalCurrency;
  }

  public void setOriginalCurrency(@Nullable String originalCurrency) {
    this.originalCurrency = originalCurrency;
  }

  public boolean isSplit() {
    return split;
  }

  public void setSplit(boolean split) {
    this.split = split;
  }

  public List<SplitRow> getSplits() {
    return splits;
  }

  public void setSplits(List<SplitRow> splits) {
    this.splits = splits;
  }

  public @Nullable UUID getToAccountId() {
    return toAccountId;
  }

  public void setToAccountId(@Nullable UUID toAccountId) {
    this.toAccountId = toAccountId;
  }

  public @Nullable BigDecimal getToAmount() {
    return toAmount;
  }

  public void setToAmount(@Nullable BigDecimal toAmount) {
    this.toAmount = toAmount;
  }

  public int getVersion() {
    return version;
  }

  public void setVersion(int version) {
    this.version = version;
  }

  public static class SplitRow {

    private @Nullable UUID categoryId;
    private @Nullable BigDecimal amount;
    private @Nullable String note;

    public @Nullable UUID getCategoryId() {
      return categoryId;
    }

    public void setCategoryId(@Nullable UUID categoryId) {
      this.categoryId = categoryId;
    }

    public @Nullable BigDecimal getAmount() {
      return amount;
    }

    public void setAmount(@Nullable BigDecimal amount) {
      this.amount = amount;
    }

    public @Nullable String getNote() {
      return note;
    }

    public void setNote(@Nullable String note) {
      this.note = note;
    }
  }
}
