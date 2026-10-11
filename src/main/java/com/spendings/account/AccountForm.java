package com.spendings.account;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;

/** Форма счёта; поля вклада заполняются только для типа «Вклад». */
public class AccountForm {

  @NotBlank
  @Size(max = 100)
  private String name = "";

  @NotNull private AccountType type = AccountType.CARD;

  @NotBlank private String currency = "BYN";

  private @Nullable BigDecimal openingBalance;

  @NotNull
  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
  private LocalDate openedOn = LocalDate.now();

  private boolean inNetWorth = true;

  private int version;

  // ---- вклад ----

  @Size(max = 100)
  private @Nullable String bank;

  @DecimalMin("0")
  @DecimalMax("100")
  private @Nullable BigDecimal annualRate;

  private @Nullable Capitalization capitalization = Capitalization.MONTHLY;

  @DecimalMin("0")
  @DecimalMax("100")
  private @Nullable BigDecimal taxRate;

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
  private @Nullable LocalDate depositOpenedOn;

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
  private @Nullable LocalDate maturesOn;

  private boolean revocable = true;

  private boolean autoRenew;

  /** Куда выплачиваются проценты вклада без капитализации; пусто — на сам вклад. */
  private @Nullable UUID payoutAccountId;

  static AccountForm of(Account account) {
    var form = new AccountForm();
    form.name = account.name();
    form.type = account.type();
    form.currency = account.currency();
    form.openingBalance = account.openingBalance();
    form.openedOn = account.openedOn();
    form.inNetWorth = account.inNetWorth();
    form.version = account.version();
    return form;
  }

  void fillDeposit(DepositTerms terms) {
    bank = terms.bank();
    annualRate = terms.annualRate();
    capitalization = terms.capitalization();
    taxRate = terms.taxRate();
    depositOpenedOn = terms.openedOn();
    maturesOn = terms.maturesOn();
    revocable = terms.revocable();
    autoRenew = terms.autoRenew();
    payoutAccountId = terms.payoutAccountId();
  }

  BigDecimal openingBalanceOrZero() {
    return openingBalance == null ? BigDecimal.ZERO : openingBalance;
  }

  LocalDate depositOpenedOnOrDefault() {
    return depositOpenedOn == null ? openedOn : depositOpenedOn;
  }

  public Capitalization[] getCapitalizations() {
    return Capitalization.values();
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public AccountType getType() {
    return type;
  }

  public void setType(AccountType type) {
    this.type = type;
  }

  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String currency) {
    this.currency = currency;
  }

  public @Nullable BigDecimal getOpeningBalance() {
    return openingBalance;
  }

  public void setOpeningBalance(@Nullable BigDecimal openingBalance) {
    this.openingBalance = openingBalance;
  }

  public LocalDate getOpenedOn() {
    return openedOn;
  }

  public void setOpenedOn(LocalDate openedOn) {
    this.openedOn = openedOn;
  }

  public boolean isInNetWorth() {
    return inNetWorth;
  }

  public void setInNetWorth(boolean inNetWorth) {
    this.inNetWorth = inNetWorth;
  }

  public int getVersion() {
    return version;
  }

  public void setVersion(int version) {
    this.version = version;
  }

  public @Nullable String getBank() {
    return bank;
  }

  public void setBank(@Nullable String bank) {
    this.bank = bank;
  }

  public @Nullable BigDecimal getAnnualRate() {
    return annualRate;
  }

  public void setAnnualRate(@Nullable BigDecimal annualRate) {
    this.annualRate = annualRate;
  }

  public @Nullable Capitalization getCapitalization() {
    return capitalization;
  }

  public void setCapitalization(@Nullable Capitalization capitalization) {
    this.capitalization = capitalization;
  }

  public @Nullable BigDecimal getTaxRate() {
    return taxRate;
  }

  public void setTaxRate(@Nullable BigDecimal taxRate) {
    this.taxRate = taxRate;
  }

  public @Nullable LocalDate getDepositOpenedOn() {
    return depositOpenedOn;
  }

  public void setDepositOpenedOn(@Nullable LocalDate depositOpenedOn) {
    this.depositOpenedOn = depositOpenedOn;
  }

  public @Nullable LocalDate getMaturesOn() {
    return maturesOn;
  }

  public void setMaturesOn(@Nullable LocalDate maturesOn) {
    this.maturesOn = maturesOn;
  }

  public boolean isRevocable() {
    return revocable;
  }

  public void setRevocable(boolean revocable) {
    this.revocable = revocable;
  }

  public boolean isAutoRenew() {
    return autoRenew;
  }

  public void setAutoRenew(boolean autoRenew) {
    this.autoRenew = autoRenew;
  }

  public @Nullable UUID getPayoutAccountId() {
    return payoutAccountId;
  }

  public void setPayoutAccountId(@Nullable UUID payoutAccountId) {
    this.payoutAccountId = payoutAccountId;
  }
}
