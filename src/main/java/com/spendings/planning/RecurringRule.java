package com.spendings.planning;

import com.spendings.operation.OperationType;
import com.spendings.planning.Schedule.Frequency;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;
import org.jspecify.annotations.Nullable;

/** Регулярная операция: шаблон (тип, счёт, категория, сумма) и расписание. */
@Entity
@Table(name = "recurring_rule")
class RecurringRule {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private @Nullable UUID id;

  @Column(nullable = false)
  private String name = "";

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private OperationType type = OperationType.EXPENSE;

  @Column(name = "account_id", nullable = false)
  private UUID accountId = new UUID(0, 0);

  @Column(name = "to_account_id")
  private @Nullable UUID toAccountId;

  @Column(name = "category_id")
  private @Nullable UUID categoryId;

  @Column(nullable = false, precision = 19, scale = 4)
  private BigDecimal amount = BigDecimal.ONE;

  @Column(name = "to_amount", precision = 19, scale = 4)
  private @Nullable BigDecimal toAmount;

  private @Nullable String payee;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Frequency frequency = Frequency.MONTHLY;

  @Column(name = "interval_count", nullable = false)
  private short intervalCount = 1;

  @Column(name = "day_of_month")
  private @Nullable Short dayOfMonth;

  @Column(name = "day_of_week")
  private @Nullable Short dayOfWeek;

  @Column(name = "starts_on", nullable = false)
  private LocalDate startsOn = LocalDate.now();

  @Column(name = "ends_on")
  private @Nullable LocalDate endsOn;

  @Column(name = "auto_post", nullable = false)
  private boolean autoPost;

  @Column(name = "tolerance_pct", nullable = false, precision = 5, scale = 2)
  private BigDecimal tolerancePct = BigDecimal.TEN;

  @Column(nullable = false)
  private boolean active = true;

  @Version private int version;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt = Instant.now();

  protected RecurringRule() {}

  static RecurringRule create() {
    return new RecurringRule();
  }

  void update(RuleForm f) {
    this.name = f.getName().strip();
    this.type = f.getType();
    this.accountId = f.requireAccountId();
    this.toAccountId = f.getType() == OperationType.TRANSFER ? f.getToAccountId() : null;
    this.categoryId = f.getType() == OperationType.TRANSFER ? null : f.getCategoryId();
    this.amount = f.requireAmount();
    this.toAmount = f.getType() == OperationType.TRANSFER ? f.getToAmount() : null;
    this.payee = f.getPayee() == null || f.getPayee().isBlank() ? null : f.getPayee().strip();
    this.frequency = f.getFrequency();
    this.intervalCount = (short) Math.max(1, f.getInterval());
    this.dayOfMonth =
        f.getFrequency() == Frequency.MONTHLY && f.getDayOfMonth() != null
            ? f.getDayOfMonth().shortValue()
            : null;
    this.dayOfWeek =
        f.getFrequency() == Frequency.WEEKLY && f.getDayOfWeek() != null
            ? f.getDayOfWeek().shortValue()
            : null;
    this.startsOn = f.getStartsOn();
    this.endsOn = f.getEndsOn();
    this.autoPost = f.isAutoPost();
    this.tolerancePct = f.getTolerancePct() == null ? BigDecimal.TEN : f.getTolerancePct();
  }

  void deactivate() {
    active = false;
  }

  void activate() {
    active = true;
  }

  Schedule schedule() {
    return new Schedule(
        frequency,
        intervalCount,
        dayOfMonth == null ? null : dayOfMonth.intValue(),
        dayOfWeek == null ? null : dayOfWeek.intValue(),
        startsOn,
        endsOn);
  }

  UUID id() {
    if (id == null) {
      throw new IllegalStateException("Правило ещё не сохранено");
    }
    return id;
  }

  String name() {
    return name;
  }

  OperationType type() {
    return type;
  }

  UUID accountId() {
    return accountId;
  }

  @Nullable UUID toAccountId() {
    return toAccountId;
  }

  @Nullable UUID categoryId() {
    return categoryId;
  }

  BigDecimal amount() {
    return amount;
  }

  @Nullable BigDecimal toAmount() {
    return toAmount;
  }

  @Nullable String payee() {
    return payee;
  }

  Frequency frequency() {
    return frequency;
  }

  int interval() {
    return intervalCount;
  }

  @Nullable Integer dayOfMonth() {
    return dayOfMonth == null ? null : dayOfMonth.intValue();
  }

  @Nullable Integer dayOfWeek() {
    return dayOfWeek == null ? null : dayOfWeek.intValue();
  }

  LocalDate startsOn() {
    return startsOn;
  }

  @Nullable LocalDate endsOn() {
    return endsOn;
  }

  boolean autoPost() {
    return autoPost;
  }

  BigDecimal tolerancePct() {
    return tolerancePct;
  }

  boolean active() {
    return active;
  }

  int version() {
    return version;
  }
}
