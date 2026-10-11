package com.spendings.account;

import com.spendings.account.DepositTerms.Capitalization;
import com.spendings.fx.Currencies;
import com.spendings.shared.DomainException;
import com.spendings.shared.NotFoundException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Публичный API модуля счетов; изменение счетов — только изнутри модуля. */
@Service
@Transactional(readOnly = true)
public class Accounts {

  private final AccountRepository accounts;
  private final DepositTermsRepository depositTerms;
  private final Currencies currencies;
  private final AccountMovements movements;

  Accounts(
      AccountRepository accounts,
      DepositTermsRepository depositTerms,
      Currencies currencies,
      AccountMovements movements) {
    this.accounts = accounts;
    this.depositTerms = depositTerms;
    this.currencies = currencies;
    this.movements = movements;
  }

  /** Все счета в порядке пользователя, архивные — в конце. */
  public List<AccountRef> all() {
    var minorUnits = minorUnitsByCurrency();
    return accounts.findAllByOrderBySortOrderAscNameAsc().stream()
        .map(a -> toRef(a, minorUnits))
        .sorted(Comparator.comparing(AccountRef::archived))
        .toList();
  }

  public List<AccountRef> active() {
    return all().stream().filter(a -> !a.archived()).toList();
  }

  public Optional<AccountRef> find(UUID id) {
    return accounts.findById(id).map(a -> toRef(a, minorUnitsByCurrency()));
  }

  public AccountRef get(UUID id) {
    return find(id).orElseThrow(() -> new NotFoundException("Счёт"));
  }

  /** Счета с текущими остатками в своей валюте. */
  public List<AccountBalance> balances() {
    Map<UUID, BigDecimal> totals = movements.totals();
    return all().stream()
        .map(
            a ->
                new AccountBalance(
                    a, a.openingBalance().add(totals.getOrDefault(a.id(), BigDecimal.ZERO))))
        .toList();
  }

  public BigDecimal balanceOf(UUID accountId) {
    AccountRef account = get(accountId);
    return account
        .openingBalance()
        .add(movements.totals().getOrDefault(accountId, BigDecimal.ZERO));
  }

  // ---- изменение: только для контроллера модуля ----

  AccountForm formFor(UUID id) {
    Account account = load(id);
    var form = AccountForm.of(account);
    depositTerms.findById(id).ifPresent(form::fillDeposit);
    return form;
  }

  @Transactional
  UUID create(AccountForm form) {
    validate(form, null);
    var account =
        new Account(
            form.getName().strip(),
            form.getType(),
            form.getCurrency(),
            form.openingBalanceOrZero(),
            form.getOpenedOn(),
            form.isInNetWorth(),
            accounts.maxSortOrder() + 1);
    UUID id = accounts.saveAndFlush(account).id();
    saveDepositTerms(id, form);
    return id;
  }

  @Transactional
  void update(UUID id, AccountForm form) {
    Account account = load(id);
    if (account.version() != form.getVersion()) {
      throw new DomainException("error.concurrentEdit");
    }
    validate(form, account);
    account.update(
        form.getName().strip(),
        form.getType(),
        form.getCurrency(),
        form.openingBalanceOrZero(),
        form.getOpenedOn(),
        form.isInNetWorth());
    saveDepositTerms(id, form);
  }

  @Transactional
  void archive(UUID id) {
    load(id).archive();
  }

  @Transactional
  void restore(UUID id) {
    load(id).restore();
  }

  /** Удалить можно только счёт без операций; остальные архивируют. */
  @Transactional
  void delete(UUID id) {
    Account account = load(id);
    if (movements.hasMovements(id)) {
      throw new DomainException("account.error.hasOperations");
    }
    depositTerms.deleteById(id);
    accounts.delete(account);
  }

  @Transactional
  void reorder(List<UUID> orderedIds) {
    Map<UUID, Account> byId =
        accounts.findAllById(orderedIds).stream()
            .collect(Collectors.toMap(Account::id, Function.identity()));
    for (int i = 0; i < orderedIds.size(); i++) {
      Account account = byId.get(orderedIds.get(i));
      if (account != null) {
        account.moveTo(i + 1);
      }
    }
  }

  private void validate(AccountForm form, @Nullable Account existing) {
    if (!currencies.exists(form.getCurrency())) {
      throw new DomainException("account.error.unknownCurrency", form.getCurrency());
    }
    if (existing != null
        && !existing.currency().equals(form.getCurrency())
        && movements.hasMovements(existing.id())) {
      throw new DomainException("account.error.currencyLocked");
    }
    int minorUnits = minorUnitsByCurrency().getOrDefault(form.getCurrency(), 2);
    if (form.openingBalanceOrZero().stripTrailingZeros().scale() > minorUnits) {
      throw new DomainException("error.tooManyDecimals", minorUnits);
    }
    if (form.getType() == AccountType.DEPOSIT) {
      if (form.getAnnualRate() == null) {
        throw new DomainException("account.error.rateRequired");
      }
      LocalDate depositOpenedOn = form.depositOpenedOnOrDefault();
      if (form.getMaturesOn() != null && !form.getMaturesOn().isAfter(depositOpenedOn)) {
        throw new DomainException("account.error.maturityBeforeOpening");
      }
    }
  }

  private void saveDepositTerms(UUID accountId, AccountForm form) {
    if (form.getType() != AccountType.DEPOSIT) {
      depositTerms.findById(accountId).ifPresent(depositTerms::delete);
      return;
    }
    DepositTerms terms =
        depositTerms.findById(accountId).orElseGet(() -> new DepositTerms(accountId));
    BigDecimal rate = form.getAnnualRate();
    terms.update(
        blankToNull(form.getBank()),
        rate == null ? BigDecimal.ZERO : rate,
        form.getCapitalization() == null ? Capitalization.MONTHLY : form.getCapitalization(),
        form.getTaxRate() == null ? BigDecimal.ZERO : form.getTaxRate(),
        form.depositOpenedOnOrDefault(),
        form.getMaturesOn(),
        form.isRevocable(),
        form.isAutoRenew());
    depositTerms.save(terms);
  }

  private Account load(UUID id) {
    return accounts.findById(id).orElseThrow(() -> new NotFoundException("Счёт"));
  }

  private Map<String, Integer> minorUnitsByCurrency() {
    return currencies.all().stream()
        .collect(Collectors.toMap(Currencies.Currency::code, Currencies.Currency::minorUnits));
  }

  private static AccountRef toRef(Account a, Map<String, Integer> minorUnits) {
    return new AccountRef(
        a.id(),
        a.name(),
        a.type(),
        a.currency(),
        minorUnits.getOrDefault(a.currency(), 2),
        a.openingBalance(),
        a.archived());
  }

  private static @Nullable String blankToNull(@Nullable String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  public record AccountBalance(AccountRef account, BigDecimal balance) {}
}
