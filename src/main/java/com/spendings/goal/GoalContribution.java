package com.spendings.goal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;
import org.jspecify.annotations.Nullable;

/** Отметка виртуального резерва: «отложил» (+) или «взял» (−), в валюте цели. */
@Entity
@Table(name = "goal_contribution")
class GoalContribution {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private @Nullable UUID id;

  @Column(name = "goal_id", nullable = false)
  private UUID goalId;

  @Column(name = "made_on", nullable = false)
  private LocalDate madeOn;

  @Column(nullable = false, precision = 19, scale = 4)
  private BigDecimal amount;

  private @Nullable String note;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt = Instant.now();

  protected GoalContribution() {
    this(new UUID(0, 0), LocalDate.EPOCH, BigDecimal.ONE, null);
  }

  GoalContribution(UUID goalId, LocalDate madeOn, BigDecimal amount, @Nullable String note) {
    this.goalId = goalId;
    this.madeOn = madeOn;
    this.amount = amount;
    this.note = note;
  }

  UUID id() {
    if (id == null) {
      throw new IllegalStateException("Взнос ещё не сохранён");
    }
    return id;
  }

  UUID goalId() {
    return goalId;
  }

  LocalDate madeOn() {
    return madeOn;
  }

  BigDecimal amount() {
    return amount;
  }

  @Nullable String note() {
    return note;
  }
}
