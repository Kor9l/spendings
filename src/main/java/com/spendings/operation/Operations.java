package com.spendings.operation;

import com.spendings.account.AccountRef;
import com.spendings.account.Accounts;
import com.spendings.category.Categories;
import com.spendings.category.CategoryKind;
import com.spendings.category.CategoryRef;
import com.spendings.fx.Currencies;
import com.spendings.operation.OperationLine.Draft;
import com.spendings.operation.RecordOperation.AdjustBalance;
import com.spendings.operation.RecordOperation.RecordExpense;
import com.spendings.operation.RecordOperation.RecordIncome;
import com.spendings.operation.RecordOperation.RecordTransfer;
import com.spendings.operation.RecordOperation.Split;
import com.spendings.shared.DomainException;
import com.spendings.shared.NotFoundException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ввод, правка и удаление операций. Здесь проверяются все правила: суммы положительные и с
 * допустимым числом знаков, категория подходит по виду, архивные счета и категории нельзя выбрать
 * заново, перевод между одной валютой не меняет сумму.
 */
@Service
@Transactional
public class Operations {

  static final int MAX_TEXT = 200;

  private final OperationRepository operations;
  private final Accounts accounts;
  private final Categories categories;
  private final Currencies currencies;
  private final ApplicationEventPublisher events;

  Operations(
      OperationRepository operations,
      Accounts accounts,
      Categories categories,
      Currencies currencies,
      ApplicationEventPublisher events) {
    this.operations = operations;
    this.accounts = accounts;
    this.categories = categories;
    this.currencies = currencies;
    this.events = events;
  }

  public UUID record(RecordOperation command) {
    Plan plan = plan(command, null);
    var operation =
        new Operation(
            plan.type(), command.date(), plan.payee(), text(command.note()), plan.lines());
    return published(operations.saveAndFlush(operation));
  }

  public void update(UUID id, int expectedVersion, RecordOperation command) {
    Operation operation = load(id);
    if (operation.version() != expectedVersion) {
      throw new DomainException("error.concurrentEdit");
    }
    Plan plan = plan(command, operation);
    operation.replace(
        plan.type(), command.date(), plan.payee(), text(command.note()), plan.lines());
    operations.flush();
  }

  /** Начисленные проценты по вкладу. */
  public UUID recordInterest(RecordIncome command) {
    Plan plan = plan(command, null);
    var operation =
        new Operation(
            plan.type(), command.date(), plan.payee(), text(command.note()), plan.lines());
    operation.markInterest();
    return operations.saveAndFlush(operation).id();
  }

  /**
   * Операция из выписки: со статусом «на проверке», в остатки и отчёты не входит до подтверждения.
   */
  public UUID recordImported(RecordOperation command, UUID batchId, String externalKey) {
    Plan plan = plan(command, null);
    var operation =
        new Operation(
            plan.type(), command.date(), plan.payee(), text(command.note()), plan.lines());
    operation.markImported(batchId, externalKey);
    return published(operations.saveAndFlush(operation));
  }

  private UUID published(Operation saved) {
    OperationLine first = saved.lines().getFirst();
    events.publishEvent(
        new OperationRecorded(
            saved.id(),
            saved.type(),
            saved.occurredOn(),
            first.accountId(),
            first.amount(),
            first.categoryId()));
    return saved.id();
  }

  public void confirm(UUID id) {
    load(id).confirm();
    operations.flush();
  }

  /** Подтверждает всё, что лежит во «Входящих» из этой партии; возвращает сколько. */
  public int confirmBatch(UUID batchId) {
    int count = 0;
    for (Operation op : operations.findByImportId(batchId)) {
      if (op.inReview()) {
        op.confirm();
        count++;
      }
    }
    operations.flush();
    return count;
  }

  /** Удаляет все операции партии, в том числе уже подтверждённые; возвращает сколько. */
  public int deleteBatch(UUID batchId) {
    List<Operation> batch = operations.findByImportId(batchId);
    operations.deleteAll(batch);
    operations.flush();
    return batch.size();
  }

  /** Меняет категорию расхода или дохода из одной строки — для разбора «Входящих». */
  public void recategorize(UUID id, UUID categoryId) {
    Operation op = load(id);
    Recorded recorded = find(id);
    RecordOperation changed =
        switch (recorded.command()) {
          case RecordExpense e when e.splits().size() == 1 ->
              new RecordExpense(
                  e.accountId(),
                  e.date(),
                  List.of(withCategory(e.splits().getFirst(), categoryId)),
                  e.payee(),
                  e.note());
          case RecordIncome i when i.splits().size() == 1 ->
              new RecordIncome(
                  i.accountId(),
                  i.date(),
                  List.of(withCategory(i.splits().getFirst(), categoryId)),
                  i.payee(),
                  i.note());
          default -> throw new DomainException("operation.error.cannotRecategorize");
        };
    Plan plan = plan(changed, op);
    op.replace(plan.type(), changed.date(), plan.payee(), text(changed.note()), plan.lines());
    operations.flush();
  }

  private static Split withCategory(Split split, UUID categoryId) {
    return new Split(
        categoryId, split.amount(), split.originalAmount(), split.originalCurrency(), split.note());
  }

  public void delete(UUID id) {
    operations.delete(load(id));
    operations.flush();
  }

  /** Команда, из которой получилась бы эта операция: для формы правки. */
  @Transactional(readOnly = true)
  public Recorded find(UUID id) {
    Operation op = load(id);
    List<OperationLine> lines = op.lines();
    RecordOperation command =
        switch (op.type()) {
          case EXPENSE ->
              new RecordExpense(
                  lines.getFirst().accountId(),
                  op.occurredOn(),
                  splits(lines, true),
                  op.payee(),
                  op.note());
          case INCOME ->
              new RecordIncome(
                  lines.getFirst().accountId(),
                  op.occurredOn(),
                  splits(lines, false),
                  op.payee(),
                  op.note());
          case TRANSFER -> {
            OperationLine from =
                lines.stream().filter(l -> l.amount().signum() < 0).findFirst().orElseThrow();
            OperationLine to =
                lines.stream().filter(l -> l.amount().signum() > 0).findFirst().orElseThrow();
            yield new RecordTransfer(
                from.accountId(),
                from.amount().negate(),
                to.accountId(),
                to.amount(),
                op.occurredOn(),
                op.note());
          }
          case ADJUSTMENT ->
              new AdjustBalance(
                  lines.getFirst().accountId(),
                  op.occurredOn(),
                  lines.getFirst().amount(),
                  op.note());
        };
    return new Recorded(id, op.version(), command);
  }

  private Plan plan(RecordOperation command, @Nullable Operation existing) {
    return switch (command) {
      case RecordExpense e ->
          new Plan(
              OperationType.EXPENSE,
              text(e.payee()),
              categorized(e.accountId(), e.splits(), CategoryKind.EXPENSE, true, existing));
      case RecordIncome i ->
          new Plan(
              OperationType.INCOME,
              text(i.payee()),
              categorized(i.accountId(), i.splits(), CategoryKind.INCOME, false, existing));
      case RecordTransfer t -> new Plan(OperationType.TRANSFER, null, transfer(t, existing));
      case AdjustBalance a -> {
        AccountRef account = account(a.accountId(), existing);
        if (a.delta().signum() == 0) {
          throw new DomainException("operation.error.adjustmentZero");
        }
        checkScale(a.delta(), account);
        yield new Plan(
            OperationType.ADJUSTMENT, null, List.of(Draft.of(account.id(), null, a.delta())));
      }
    };
  }

  private List<Draft> categorized(
      UUID accountId,
      List<Split> splits,
      CategoryKind kind,
      boolean outflow,
      @Nullable Operation existing) {
    if (splits.isEmpty()) {
      throw new DomainException("operation.error.categoryRequired");
    }
    AccountRef account = account(accountId, existing);
    return splits.stream()
        .map(
            split -> {
              CategoryRef category = category(split.categoryId(), kind, existing);
              positive(split.amount(), account);
              BigDecimal originalAmount = null;
              String originalCurrency = null;
              if (split.originalAmount() != null) {
                originalCurrency = split.originalCurrency();
                if (originalCurrency == null || !currencies.exists(originalCurrency)) {
                  throw new DomainException("operation.error.originalCurrencyRequired");
                }
                if (split.originalAmount().signum() <= 0) {
                  throw new DomainException("operation.error.amountPositive");
                }
                if (!originalCurrency.equals(account.currency())) {
                  originalAmount = split.originalAmount();
                } else {
                  originalCurrency = null;
                }
              }
              BigDecimal amount = outflow ? split.amount().negate() : split.amount();
              return new Draft(
                  account.id(),
                  category.id(),
                  amount,
                  originalAmount,
                  originalCurrency,
                  text(split.note()));
            })
        .toList();
  }

  private List<Draft> transfer(RecordTransfer t, @Nullable Operation existing) {
    if (t.fromAccountId().equals(t.toAccountId())) {
      throw new DomainException("operation.error.sameAccount");
    }
    AccountRef from = account(t.fromAccountId(), existing);
    AccountRef to = account(t.toAccountId(), existing);
    positive(t.fromAmount(), from);
    positive(t.toAmount(), to);
    if (from.currency().equals(to.currency()) && t.fromAmount().compareTo(t.toAmount()) != 0) {
      throw new DomainException("operation.error.transferAmountsDiffer");
    }
    return List.of(
        Draft.of(from.id(), null, t.fromAmount().negate()), Draft.of(to.id(), null, t.toAmount()));
  }

  /** Архивный счёт можно оставить в правке старой операции, но не выбрать заново. */
  private AccountRef account(UUID id, @Nullable Operation existing) {
    AccountRef account =
        accounts.find(id).orElseThrow(() -> new DomainException("operation.error.accountRequired"));
    boolean alreadyUsed =
        existing != null && existing.lines().stream().anyMatch(l -> l.accountId().equals(id));
    if (account.archived() && !alreadyUsed) {
      throw new DomainException("operation.error.accountArchived", account.name());
    }
    return account;
  }

  private CategoryRef category(UUID id, CategoryKind kind, @Nullable Operation existing) {
    CategoryRef category =
        categories
            .find(id)
            .orElseThrow(() -> new DomainException("operation.error.categoryRequired"));
    if (category.kind() != kind) {
      throw new DomainException("operation.error.categoryKindMismatch", category.displayName());
    }
    boolean alreadyUsed =
        existing != null
            && existing.lines().stream().anyMatch(l -> Objects.equals(l.categoryId(), id));
    if (category.archived() && !alreadyUsed) {
      throw new DomainException("operation.error.categoryArchived", category.displayName());
    }
    return category;
  }

  private static void positive(BigDecimal amount, AccountRef account) {
    if (amount.signum() <= 0) {
      throw new DomainException("operation.error.amountPositive");
    }
    checkScale(amount, account);
  }

  private static void checkScale(BigDecimal amount, AccountRef account) {
    if (amount.stripTrailingZeros().scale() > account.minorUnits()) {
      throw new DomainException("error.tooManyDecimals", account.minorUnits());
    }
  }

  private static List<Split> splits(List<OperationLine> lines, boolean outflow) {
    return lines.stream()
        .map(
            l ->
                new Split(
                    Objects.requireNonNull(l.categoryId()),
                    outflow ? l.amount().negate() : l.amount(),
                    l.originalAmount(),
                    l.originalCurrency(),
                    l.note()))
        .toList();
  }

  private static @Nullable String text(@Nullable String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String stripped = value.strip();
    if (stripped.length() > MAX_TEXT) {
      throw new DomainException("operation.error.textTooLong", MAX_TEXT);
    }
    return stripped;
  }

  private Operation load(UUID id) {
    return operations.findById(id).orElseThrow(() -> new NotFoundException("Операция"));
  }

  private record Plan(OperationType type, @Nullable String payee, List<Draft> lines) {}

  /** Сохранённая операция в виде команды и её версия для оптимистической блокировки. */
  public record Recorded(UUID id, int version, RecordOperation command) {

    public LocalDate date() {
      return command.date();
    }
  }
}
