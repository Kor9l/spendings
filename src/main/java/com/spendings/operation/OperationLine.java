package com.spendings.operation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "operation_line")
class OperationLine {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private @Nullable UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "operation_id", nullable = false)
  private Operation operation;

  @Column(nullable = false)
  private short position;

  @Column(name = "account_id", nullable = false)
  private UUID accountId;

  @Column(name = "category_id")
  private @Nullable UUID categoryId;

  /** «−» списание, «+» поступление, в валюте счёта. */
  @Column(nullable = false, precision = 19, scale = 4)
  private BigDecimal amount;

  @Column(name = "original_amount", precision = 19, scale = 4)
  private @Nullable BigDecimal originalAmount;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "original_currency", length = 3)
  private @Nullable String originalCurrency;

  private @Nullable String note;

  protected OperationLine() {
    this.operation = new Operation();
    this.accountId = new UUID(0, 0);
    this.amount = BigDecimal.ZERO;
  }

  OperationLine(Operation operation, int position, Draft draft) {
    this.operation = operation;
    this.position = (short) position;
    this.accountId = draft.accountId();
    this.categoryId = draft.categoryId();
    this.amount = draft.amount();
    this.originalAmount = draft.originalAmount();
    this.originalCurrency = draft.originalCurrency();
    this.note = draft.note();
  }

  UUID accountId() {
    return accountId;
  }

  @Nullable UUID categoryId() {
    return categoryId;
  }

  BigDecimal amount() {
    return amount;
  }

  @Nullable BigDecimal originalAmount() {
    return originalAmount;
  }

  @Nullable String originalCurrency() {
    return originalCurrency;
  }

  @Nullable String note() {
    return note;
  }

  /** Строка до сохранения, уже со знаком. */
  record Draft(
      UUID accountId,
      @Nullable UUID categoryId,
      BigDecimal amount,
      @Nullable BigDecimal originalAmount,
      @Nullable String originalCurrency,
      @Nullable String note) {

    static Draft of(UUID accountId, @Nullable UUID categoryId, BigDecimal amount) {
      return new Draft(accountId, categoryId, amount, null, null, null);
    }
  }
}
