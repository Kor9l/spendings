package com.spendings.account;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "account")
class Account {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private @Nullable UUID id;

  @Column(nullable = false)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private AccountType type;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(nullable = false, length = 3)
  private String currency;

  @Column(name = "opening_balance", nullable = false, precision = 19, scale = 4)
  private BigDecimal openingBalance;

  @Column(name = "opened_on", nullable = false)
  private LocalDate openedOn;

  @Column(name = "in_net_worth", nullable = false)
  private boolean inNetWorth;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  @Column(name = "archived_at")
  private @Nullable Instant archivedAt;

  @Version private int version;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected Account() {
    this("", AccountType.OTHER, "BYN", BigDecimal.ZERO, LocalDate.EPOCH, true, 0);
  }

  Account(
      String name,
      AccountType type,
      String currency,
      BigDecimal openingBalance,
      LocalDate openedOn,
      boolean inNetWorth,
      int sortOrder) {
    this.name = name;
    this.type = type;
    this.currency = currency;
    this.openingBalance = openingBalance;
    this.openedOn = openedOn;
    this.inNetWorth = inNetWorth;
    this.sortOrder = sortOrder;
    this.createdAt = Instant.now();
    this.updatedAt = createdAt;
  }

  void update(
      String name,
      AccountType type,
      String currency,
      BigDecimal openingBalance,
      LocalDate openedOn,
      boolean inNetWorth) {
    this.name = name;
    this.type = type;
    this.currency = currency;
    this.openingBalance = openingBalance;
    this.openedOn = openedOn;
    this.inNetWorth = inNetWorth;
    this.updatedAt = Instant.now();
  }

  void archive() {
    archivedAt = Instant.now();
    updatedAt = archivedAt;
  }

  void restore() {
    archivedAt = null;
    updatedAt = Instant.now();
  }

  void moveTo(int sortOrder) {
    this.sortOrder = sortOrder;
  }

  UUID id() {
    if (id == null) {
      throw new IllegalStateException("Счёт ещё не сохранён");
    }
    return id;
  }

  String name() {
    return name;
  }

  AccountType type() {
    return type;
  }

  String currency() {
    return currency;
  }

  BigDecimal openingBalance() {
    return openingBalance;
  }

  Instant createdAt() {
    return createdAt;
  }

  LocalDate openedOn() {
    return openedOn;
  }

  boolean inNetWorth() {
    return inNetWorth;
  }

  int sortOrder() {
    return sortOrder;
  }

  boolean archived() {
    return archivedAt != null;
  }

  int version() {
    return version;
  }
}
