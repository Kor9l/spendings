package com.spendings.importing;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Как читать выписку: где дата, сумма и описание. Номера колонок — с нуля, {@code null} — колонки
 * нет. Сумма — либо одной колонкой со знаком, либо двумя: списание и зачисление.
 */
public class ImportMapping {

  private @Nullable UUID accountId;
  private @Nullable UUID profileId;
  private String encoding = "UTF-8";
  private String delimiter = ";";
  private int headerRow;
  private @Nullable Integer dateColumn;
  private String dateFormat = "dd.MM.yyyy";
  private @Nullable Integer amountColumn;
  private @Nullable Integer debitColumn;
  private @Nullable Integer creditColumn;
  private boolean invertSign;
  private @Nullable Integer descriptionColumn;
  private @Nullable Integer originalAmountColumn;
  private @Nullable Integer originalCurrencyColumn;
  private boolean includeProbableDuplicates;
  private @Nullable String saveAsProfile;

  /**
   * Первая догадка по файлу: строка заголовков — первая, где есть текстовая «Дата», колонки — по
   * знакомым названиям.
   */
  static ImportMapping guess(TableFile table) {
    var mapping = new ImportMapping();
    mapping.encoding = table.encoding();
    mapping.delimiter = String.valueOf(table.delimiter());
    List<List<String>> rows = table.rows();
    for (int r = 0; r < Math.min(rows.size(), 30); r++) {
      List<String> row = rows.get(r);
      Integer date = find(row, "дата операции", "дата транзакции", "дата", "date");
      if (date != null) {
        mapping.headerRow = r;
        mapping.dateColumn = date;
        mapping.amountColumn =
            find(
                row,
                "сумма в валюте счета",
                "сумма в валюте счёта",
                "сумма операции",
                "сумма",
                "amount");
        mapping.debitColumn = find(row, "расход", "списание", "дебет", "debit");
        mapping.creditColumn = find(row, "приход", "зачисление", "кредит", "credit");
        if (mapping.debitColumn != null || mapping.creditColumn != null) {
          mapping.amountColumn = null;
        }
        mapping.descriptionColumn =
            find(
                row,
                "описание",
                "назначение",
                "операция",
                "детали",
                "контрагент",
                "место",
                "description",
                "payee");
        if (mapping.descriptionColumn != null
            && mapping.descriptionColumn.equals(mapping.dateColumn)) {
          mapping.descriptionColumn = null;
        }
        mapping.originalCurrencyColumn = find(row, "валюта операции", "валюта");
        return mapping;
      }
    }
    // заголовка нет: дата — первая колонка, сумма — первая числовая после неё
    mapping.dateColumn = 0;
    return mapping;
  }

  private static @Nullable Integer find(List<String> row, String... names) {
    for (String name : names) {
      for (int i = 0; i < row.size(); i++) {
        if (row.get(i)
            .toLowerCase(Locale.ROOT)
            .replace('ё', 'е')
            .contains(name.replace('ё', 'е'))) {
          return i;
        }
      }
    }
    return null;
  }

  boolean hasAmount() {
    return amountColumn != null || debitColumn != null || creditColumn != null;
  }

  char delimiterChar() {
    if ("\\t".equals(delimiter) || "tab".equalsIgnoreCase(delimiter)) {
      return '\t';
    }
    return delimiter.isEmpty() ? ';' : delimiter.charAt(0);
  }

  public @Nullable UUID getAccountId() {
    return accountId;
  }

  public void setAccountId(@Nullable UUID accountId) {
    this.accountId = accountId;
  }

  public @Nullable UUID getProfileId() {
    return profileId;
  }

  public void setProfileId(@Nullable UUID profileId) {
    this.profileId = profileId;
  }

  public String getEncoding() {
    return encoding;
  }

  public void setEncoding(String encoding) {
    this.encoding = encoding;
  }

  public String getDelimiter() {
    return delimiter;
  }

  public void setDelimiter(String delimiter) {
    this.delimiter = delimiter;
  }

  public int getHeaderRow() {
    return headerRow;
  }

  public void setHeaderRow(int headerRow) {
    this.headerRow = Math.max(0, headerRow);
  }

  public @Nullable Integer getDateColumn() {
    return dateColumn;
  }

  public void setDateColumn(@Nullable Integer dateColumn) {
    this.dateColumn = dateColumn;
  }

  public String getDateFormat() {
    return dateFormat;
  }

  public void setDateFormat(String dateFormat) {
    this.dateFormat = dateFormat;
  }

  public @Nullable Integer getAmountColumn() {
    return amountColumn;
  }

  public void setAmountColumn(@Nullable Integer amountColumn) {
    this.amountColumn = amountColumn;
  }

  public @Nullable Integer getDebitColumn() {
    return debitColumn;
  }

  public void setDebitColumn(@Nullable Integer debitColumn) {
    this.debitColumn = debitColumn;
  }

  public @Nullable Integer getCreditColumn() {
    return creditColumn;
  }

  public void setCreditColumn(@Nullable Integer creditColumn) {
    this.creditColumn = creditColumn;
  }

  public boolean isInvertSign() {
    return invertSign;
  }

  public void setInvertSign(boolean invertSign) {
    this.invertSign = invertSign;
  }

  public @Nullable Integer getDescriptionColumn() {
    return descriptionColumn;
  }

  public void setDescriptionColumn(@Nullable Integer descriptionColumn) {
    this.descriptionColumn = descriptionColumn;
  }

  public @Nullable Integer getOriginalAmountColumn() {
    return originalAmountColumn;
  }

  public void setOriginalAmountColumn(@Nullable Integer originalAmountColumn) {
    this.originalAmountColumn = originalAmountColumn;
  }

  public @Nullable Integer getOriginalCurrencyColumn() {
    return originalCurrencyColumn;
  }

  public void setOriginalCurrencyColumn(@Nullable Integer originalCurrencyColumn) {
    this.originalCurrencyColumn = originalCurrencyColumn;
  }

  public boolean isIncludeProbableDuplicates() {
    return includeProbableDuplicates;
  }

  public void setIncludeProbableDuplicates(boolean includeProbableDuplicates) {
    this.includeProbableDuplicates = includeProbableDuplicates;
  }

  public @Nullable String getSaveAsProfile() {
    return saveAsProfile;
  }

  public void setSaveAsProfile(@Nullable String saveAsProfile) {
    this.saveAsProfile = saveAsProfile;
  }
}
