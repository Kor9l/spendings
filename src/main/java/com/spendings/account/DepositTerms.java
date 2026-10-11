package com.spendings.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Условия вклада. Ключ совпадает с ключом счёта. График процентов — на этапе M6. */
@Entity
@Table(name = "deposit_terms")
class DepositTerms {

  @Id
  @Column(name = "account_id")
  private UUID accountId;

  private @Nullable String bank;

  @Column(name = "annual_rate", nullable = false, precision = 7, scale = 4)
  private BigDecimal annualRate;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Capitalization capitalization;

  @Column(name = "tax_rate", nullable = false, precision = 5, scale = 2)
  private BigDecimal taxRate;

  @Column(name = "opened_on", nullable = false)
  private LocalDate openedOn;

  @Column(name = "matures_on")
  private @Nullable LocalDate maturesOn;

  @Column(nullable = false)
  private boolean revocable;

  @Column(name = "auto_renew", nullable = false)
  private boolean autoRenew;

  protected DepositTerms() {
    this(new UUID(0, 0));
  }

  DepositTerms(UUID accountId) {
    this.accountId = accountId;
    this.annualRate = BigDecimal.ZERO;
    this.capitalization = Capitalization.MONTHLY;
    this.taxRate = BigDecimal.ZERO;
    this.openedOn = LocalDate.now();
    this.revocable = true;
  }

  void update(
      @Nullable String bank,
      BigDecimal annualRate,
      Capitalization capitalization,
      BigDecimal taxRate,
      LocalDate openedOn,
      @Nullable LocalDate maturesOn,
      boolean revocable,
      boolean autoRenew) {
    this.bank = bank;
    this.annualRate = annualRate;
    this.capitalization = capitalization;
    this.taxRate = taxRate;
    this.openedOn = openedOn;
    this.maturesOn = maturesOn;
    this.revocable = revocable;
    this.autoRenew = autoRenew;
  }

  @Nullable String bank() {
    return bank;
  }

  BigDecimal annualRate() {
    return annualRate;
  }

  Capitalization capitalization() {
    return capitalization;
  }

  BigDecimal taxRate() {
    return taxRate;
  }

  LocalDate openedOn() {
    return openedOn;
  }

  @Nullable LocalDate maturesOn() {
    return maturesOn;
  }

  boolean revocable() {
    return revocable;
  }

  boolean autoRenew() {
    return autoRenew;
  }

  enum Capitalization {
    NONE,
    MONTHLY,
    QUARTERLY,
    AT_MATURITY
  }
}
