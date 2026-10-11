package com.spendings.goal;

import com.spendings.account.AccountRef;
import com.spendings.goal.Goal.AccountShare;
import com.spendings.goal.Goal.Funding;
import com.spendings.goal.Goal.Kind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;

public class GoalForm {

  @NotBlank
  @Size(max = 80)
  private String name = "";

  @Size(max = 16)
  private @Nullable String icon;

  private Kind kind = Kind.AMOUNT;
  private @Nullable BigDecimal targetAmount;
  private @Nullable Integer targetMonths = 6;
  private String currency = "BYN";

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
  private @Nullable LocalDate targetDate;

  private Funding funding = Funding.ACCOUNTS;
  private int priority;
  private int version;
  private List<ShareRow> accounts = new ArrayList<>();

  /** Пустая форма: все активные счета в списке, ни один не выбран. */
  static GoalForm empty(List<AccountRef> active, String currency) {
    var form = new GoalForm();
    form.currency = currency;
    form.accounts = rows(active, List.of());
    return form;
  }

  static GoalForm of(Goal goal, List<AccountRef> active) {
    var form = new GoalForm();
    form.name = goal.name();
    form.icon = goal.icon();
    form.kind = goal.kind();
    form.targetAmount = goal.targetAmount();
    form.targetMonths = goal.targetMonths() == null ? 6 : goal.targetMonths().intValue();
    form.currency = goal.currency();
    form.targetDate = goal.targetDate();
    form.funding = goal.funding();
    form.priority = goal.priority();
    form.version = goal.version();
    form.accounts = rows(active, goal.accounts());
    return form;
  }

  private static List<ShareRow> rows(List<AccountRef> active, List<AccountShare> chosen) {
    List<ShareRow> rows = new ArrayList<>();
    for (AccountRef account : active) {
      var row = new ShareRow();
      row.setAccountId(account.id());
      row.setLabel(account.icon() + " " + account.name() + " · " + account.currency());
      row.setSharePct(BigDecimal.valueOf(100));
      chosen.stream()
          .filter(s -> s.accountId().equals(account.id()))
          .findFirst()
          .ifPresent(
              s -> {
                row.setSelected(true);
                row.setSharePct(s.sharePct().stripTrailingZeros());
              });
      rows.add(row);
    }
    return rows;
  }

  /** Подписи счетов теряются при отправке формы — восстанавливаем их для повторного показа. */
  void relabel(List<AccountRef> active) {
    for (ShareRow row : accounts) {
      active.stream()
          .filter(a -> a.id().equals(row.getAccountId()))
          .findFirst()
          .ifPresent(a -> row.setLabel(a.icon() + " " + a.name() + " · " + a.currency()));
    }
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public @Nullable String getIcon() {
    return icon;
  }

  public void setIcon(@Nullable String icon) {
    this.icon = icon;
  }

  public Kind getKind() {
    return kind;
  }

  public void setKind(Kind kind) {
    this.kind = kind;
  }

  public @Nullable BigDecimal getTargetAmount() {
    return targetAmount;
  }

  public void setTargetAmount(@Nullable BigDecimal targetAmount) {
    this.targetAmount = targetAmount;
  }

  public @Nullable Integer getTargetMonths() {
    return targetMonths;
  }

  public void setTargetMonths(@Nullable Integer targetMonths) {
    this.targetMonths = targetMonths;
  }

  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String currency) {
    this.currency = currency;
  }

  public @Nullable LocalDate getTargetDate() {
    return targetDate;
  }

  public void setTargetDate(@Nullable LocalDate targetDate) {
    this.targetDate = targetDate;
  }

  public Funding getFunding() {
    return funding;
  }

  public void setFunding(Funding funding) {
    this.funding = funding;
  }

  public int getPriority() {
    return priority;
  }

  public void setPriority(int priority) {
    this.priority = priority;
  }

  public int getVersion() {
    return version;
  }

  public void setVersion(int version) {
    this.version = version;
  }

  public List<ShareRow> getAccounts() {
    return accounts;
  }

  public void setAccounts(List<ShareRow> accounts) {
    this.accounts = accounts;
  }

  public static class ShareRow {

    private @Nullable UUID accountId;
    private String label = "";
    private boolean selected;
    private @Nullable BigDecimal sharePct;

    public UUID getAccountId() {
      if (accountId == null) {
        throw new IllegalStateException("Не выбран счёт");
      }
      return accountId;
    }

    public void setAccountId(UUID accountId) {
      this.accountId = accountId;
    }

    public String getLabel() {
      return label;
    }

    public void setLabel(String label) {
      this.label = label;
    }

    public boolean isSelected() {
      return selected;
    }

    public void setSelected(boolean selected) {
      this.selected = selected;
    }

    public @Nullable BigDecimal getSharePct() {
      return sharePct;
    }

    public void setSharePct(@Nullable BigDecimal sharePct) {
      this.sharePct = sharePct;
    }
  }
}
