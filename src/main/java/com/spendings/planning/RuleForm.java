package com.spendings.planning;

import com.spendings.operation.OperationType;
import com.spendings.planning.Schedule.Frequency;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;

public class RuleForm {

  @NotBlank
  @Size(max = 80)
  private String name = "";

  private OperationType type = OperationType.EXPENSE;
  private @Nullable UUID accountId;
  private @Nullable UUID toAccountId;
  private @Nullable UUID categoryId;
  private @Nullable BigDecimal amount;
  private @Nullable BigDecimal toAmount;
  private @Nullable String payee;
  private Frequency frequency = Frequency.MONTHLY;
  private int interval = 1;
  private @Nullable Integer dayOfMonth = LocalDate.now().getDayOfMonth();
  private @Nullable Integer dayOfWeek = LocalDate.now().getDayOfWeek().getValue();

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
  private LocalDate startsOn = LocalDate.now();

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
  private @Nullable LocalDate endsOn;

  private boolean autoPost;
  private @Nullable BigDecimal tolerancePct = BigDecimal.TEN;
  private int version;

  static RuleForm of(RecurringRule r) {
    var f = new RuleForm();
    f.name = r.name();
    f.type = r.type();
    f.accountId = r.accountId();
    f.toAccountId = r.toAccountId();
    f.categoryId = r.categoryId();
    f.amount = r.amount();
    f.toAmount = r.toAmount();
    f.payee = r.payee();
    f.frequency = r.frequency();
    f.interval = r.interval();
    f.dayOfMonth = r.dayOfMonth() == null ? r.startsOn().getDayOfMonth() : r.dayOfMonth();
    f.dayOfWeek = r.dayOfWeek() == null ? r.startsOn().getDayOfWeek().getValue() : r.dayOfWeek();
    f.startsOn = r.startsOn();
    f.endsOn = r.endsOn();
    f.autoPost = r.autoPost();
    f.tolerancePct = r.tolerancePct();
    f.version = r.version();
    return f;
  }

  UUID requireAccountId() {
    if (accountId == null) {
      throw new com.spendings.shared.DomainException("operation.error.accountRequired");
    }
    return accountId;
  }

  BigDecimal requireAmount() {
    if (amount == null || amount.signum() <= 0) {
      throw new com.spendings.shared.DomainException("operation.error.amountPositive");
    }
    return amount;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public OperationType getType() {
    return type;
  }

  public void setType(OperationType type) {
    this.type = type;
  }

  public @Nullable UUID getAccountId() {
    return accountId;
  }

  public void setAccountId(@Nullable UUID accountId) {
    this.accountId = accountId;
  }

  public @Nullable UUID getToAccountId() {
    return toAccountId;
  }

  public void setToAccountId(@Nullable UUID toAccountId) {
    this.toAccountId = toAccountId;
  }

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

  public @Nullable BigDecimal getToAmount() {
    return toAmount;
  }

  public void setToAmount(@Nullable BigDecimal toAmount) {
    this.toAmount = toAmount;
  }

  public @Nullable String getPayee() {
    return payee;
  }

  public void setPayee(@Nullable String payee) {
    this.payee = payee;
  }

  public Frequency getFrequency() {
    return frequency;
  }

  public void setFrequency(Frequency frequency) {
    this.frequency = frequency;
  }

  public int getInterval() {
    return interval;
  }

  public void setInterval(int interval) {
    this.interval = interval;
  }

  public @Nullable Integer getDayOfMonth() {
    return dayOfMonth;
  }

  public void setDayOfMonth(@Nullable Integer dayOfMonth) {
    this.dayOfMonth = dayOfMonth;
  }

  public @Nullable Integer getDayOfWeek() {
    return dayOfWeek;
  }

  public void setDayOfWeek(@Nullable Integer dayOfWeek) {
    this.dayOfWeek = dayOfWeek;
  }

  public LocalDate getStartsOn() {
    return startsOn;
  }

  public void setStartsOn(LocalDate startsOn) {
    this.startsOn = startsOn;
  }

  public @Nullable LocalDate getEndsOn() {
    return endsOn;
  }

  public void setEndsOn(@Nullable LocalDate endsOn) {
    this.endsOn = endsOn;
  }

  public boolean isAutoPost() {
    return autoPost;
  }

  public void setAutoPost(boolean autoPost) {
    this.autoPost = autoPost;
  }

  public @Nullable BigDecimal getTolerancePct() {
    return tolerancePct;
  }

  public void setTolerancePct(@Nullable BigDecimal tolerancePct) {
    this.tolerancePct = tolerancePct;
  }

  public int getVersion() {
    return version;
  }

  public void setVersion(int version) {
    this.version = version;
  }
}
