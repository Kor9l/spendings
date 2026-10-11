package com.spendings.operation;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;
import org.jspecify.annotations.Nullable;

/**
 * Агрегат «операция»: заголовок и строки. Строки меняются только целиком, через {@link #replace},
 * чтобы инварианты типа операции проверялись в одном месте — {@link Operations}.
 */
@Entity
@Table(name = "operation")
class Operation {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private @Nullable UUID id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private OperationType type;

  @Column(name = "occurred_on", nullable = false)
  private LocalDate occurredOn;

  private @Nullable String payee;

  private @Nullable String note;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Source source = Source.MANUAL;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Status status = Status.CLEARED;

  @Column(name = "import_id")
  private @Nullable UUID importId;

  @Column(name = "external_key")
  private @Nullable String externalKey;

  @Version private int version;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @OneToMany(mappedBy = "operation", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("position")
  private List<OperationLine> lines = new ArrayList<>();

  protected Operation() {
    this.type = OperationType.EXPENSE;
    this.occurredOn = LocalDate.EPOCH;
    this.createdAt = Instant.now();
    this.updatedAt = createdAt;
  }

  Operation(
      OperationType type,
      LocalDate occurredOn,
      @Nullable String payee,
      @Nullable String note,
      List<OperationLine.Draft> lines) {
    this.type = type;
    this.occurredOn = occurredOn;
    this.payee = payee;
    this.note = note;
    this.createdAt = Instant.now();
    this.updatedAt = createdAt;
    setLines(lines);
  }

  void replace(
      OperationType type,
      LocalDate occurredOn,
      @Nullable String payee,
      @Nullable String note,
      List<OperationLine.Draft> lines) {
    this.type = type;
    this.occurredOn = occurredOn;
    this.payee = payee;
    this.note = note;
    this.updatedAt = Instant.now();
    this.lines.clear();
    setLines(lines);
  }

  private void setLines(List<OperationLine.Draft> drafts) {
    for (int i = 0; i < drafts.size(); i++) {
      lines.add(new OperationLine(this, i, drafts.get(i)));
    }
  }

  /** Из выписки: ждёт подтверждения во «Входящих», в остатки и отчёты пока не входит. */
  void markImported(UUID importId, String externalKey) {
    this.source = Source.IMPORT;
    this.status = Status.REVIEW;
    this.importId = importId;
    this.externalKey = externalKey;
  }

  void markInterest() {
    this.source = Source.INTEREST;
  }

  void confirm() {
    this.status = Status.CLEARED;
    this.updatedAt = Instant.now();
  }

  boolean inReview() {
    return status == Status.REVIEW;
  }

  UUID id() {
    if (id == null) {
      throw new IllegalStateException("Операция ещё не сохранена");
    }
    return id;
  }

  OperationType type() {
    return type;
  }

  LocalDate occurredOn() {
    return occurredOn;
  }

  @Nullable String payee() {
    return payee;
  }

  @Nullable String note() {
    return note;
  }

  int version() {
    return version;
  }

  List<OperationLine> lines() {
    return Collections.unmodifiableList(lines);
  }

  enum Source {
    MANUAL,
    IMPORT,
    RECURRING,
    BOT,
    /** Проценты по вкладу: в темп накопления целей не входят — их учитывает ставка. */
    INTEREST
  }

  enum Status {
    CLEARED,
    REVIEW
  }
}
