package com.spendings.goal;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "goal")
class Goal {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private @Nullable UUID id;

  @Column(nullable = false)
  private String name;

  private @Nullable String icon;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Kind kind;

  @Column(name = "target_amount", precision = 19, scale = 4)
  private @Nullable BigDecimal targetAmount;

  @Column(name = "target_months")
  private @Nullable Short targetMonths;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(nullable = false, length = 3)
  private String currency;

  @Column(name = "target_date")
  private @Nullable LocalDate targetDate;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Funding funding;

  @Column(nullable = false)
  private int priority;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Status status = Status.ACTIVE;

  @Version private int version;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt = Instant.now();

  @Column(name = "achieved_at")
  private @Nullable Instant achievedAt;

  @ElementCollection
  @CollectionTable(name = "goal_account", joinColumns = @JoinColumn(name = "goal_id"))
  private List<AccountShare> accounts = new ArrayList<>();

  protected Goal() {
    this.name = "";
    this.kind = Kind.AMOUNT;
    this.currency = "BYN";
    this.funding = Funding.ACCOUNTS;
  }

  Goal(String name) {
    this();
    this.name = name;
  }

  void update(
      String name,
      @Nullable String icon,
      Kind kind,
      @Nullable BigDecimal targetAmount,
      @Nullable Short targetMonths,
      String currency,
      @Nullable LocalDate targetDate,
      Funding funding,
      int priority,
      List<AccountShare> accounts) {
    this.name = name;
    this.icon = icon;
    this.kind = kind;
    this.targetAmount = kind == Kind.AMOUNT ? targetAmount : null;
    this.targetMonths = kind == Kind.MONTHS_OF_EXPENSES ? targetMonths : null;
    this.currency = currency;
    this.targetDate = targetDate;
    this.funding = funding;
    this.priority = priority;
    this.accounts.clear();
    if (funding == Funding.ACCOUNTS) {
      this.accounts.addAll(accounts);
    }
  }

  void achieve() {
    status = Status.ACHIEVED;
    achievedAt = Instant.now();
  }

  void archive() {
    status = Status.ARCHIVED;
  }

  void restore() {
    status = Status.ACTIVE;
    achievedAt = null;
  }

  UUID id() {
    if (id == null) {
      throw new IllegalStateException("Цель ещё не сохранена");
    }
    return id;
  }

  String name() {
    return name;
  }

  @Nullable String icon() {
    return icon;
  }

  Kind kind() {
    return kind;
  }

  @Nullable BigDecimal targetAmount() {
    return targetAmount;
  }

  @Nullable Short targetMonths() {
    return targetMonths;
  }

  String currency() {
    return currency;
  }

  @Nullable LocalDate targetDate() {
    return targetDate;
  }

  Funding funding() {
    return funding;
  }

  int priority() {
    return priority;
  }

  Status status() {
    return status;
  }

  int version() {
    return version;
  }

  Instant createdAt() {
    return createdAt;
  }

  List<AccountShare> accounts() {
    return Collections.unmodifiableList(accounts);
  }

  public enum Kind {
    AMOUNT,
    MONTHS_OF_EXPENSES
  }

  public enum Funding {
    ACCOUNTS,
    MANUAL
  }

  public enum Status {
    ACTIVE,
    ACHIEVED,
    ARCHIVED
  }

  /** Доля остатка счёта, которая относится к цели. */
  @Embeddable
  record AccountShare(
      @Column(name = "account_id", nullable = false) UUID accountId,
      @Column(name = "share_pct", nullable = false, precision = 5, scale = 2)
          BigDecimal sharePct) {}
}
