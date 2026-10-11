package com.spendings.importing;

import com.spendings.shared.Amounts;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** Строки выписки → даты, суммы со знаком и описания по правилам {@link ImportMapping}. */
final class RowParser {

  /** Если заданный формат не подошёл, пробуем частые. */
  private static final List<String> FALLBACK_DATES =
      List.of(
          "dd.MM.yyyy",
          "dd.MM.yyyy HH:mm:ss",
          "dd.MM.yyyy HH:mm",
          "yyyy-MM-dd",
          "dd.MM.yy",
          "dd/MM/yyyy",
          "yyyy-MM-dd HH:mm:ss");

  private RowParser() {}

  static List<ParsedRow> parse(TableFile table, ImportMapping mapping) {
    List<ParsedRow> parsed = new ArrayList<>();
    List<List<String>> rows = table.rows();
    for (int r = mapping.getHeaderRow() + 1; r < rows.size(); r++) {
      parsed.add(parse(r, rows.get(r), mapping));
    }
    return parsed;
  }

  static ParsedRow parse(int index, List<String> row, ImportMapping mapping) {
    String rawDate = cell(row, mapping.getDateColumn());
    LocalDate date = parseDate(rawDate, mapping.getDateFormat());
    if (date == null) {
      return ParsedRow.error(index, row, "Не понял дату «" + rawDate + "»");
    }
    BigDecimal amount;
    try {
      amount = amount(row, mapping);
    } catch (IllegalArgumentException e) {
      return ParsedRow.error(index, row, "Не понял сумму");
    }
    if (amount == null || amount.signum() == 0) {
      return ParsedRow.error(index, row, "Нет суммы");
    }
    String description = cell(row, mapping.getDescriptionColumn());
    BigDecimal originalAmount = null;
    String originalCurrency = null;
    String rawOriginal = cell(row, mapping.getOriginalAmountColumn());
    String rawCurrency = cell(row, mapping.getOriginalCurrencyColumn()).toUpperCase(Locale.ROOT);
    if (!rawOriginal.isEmpty() && rawCurrency.matches("[A-Z]{3}")) {
      try {
        originalAmount = Amounts.parse(rawOriginal).abs();
        originalCurrency = rawCurrency;
      } catch (IllegalArgumentException ignored) {
        // исходная сумма необязательна
      }
    }
    return new ParsedRow(
        index, row, date, amount, description, originalAmount, originalCurrency, null);
  }

  private static @Nullable BigDecimal amount(List<String> row, ImportMapping mapping) {
    BigDecimal amount;
    if (mapping.getAmountColumn() != null) {
      String raw = cell(row, mapping.getAmountColumn());
      if (raw.isEmpty()) {
        return null;
      }
      amount = Amounts.parse(raw);
    } else {
      BigDecimal debit = optional(cell(row, mapping.getDebitColumn()));
      BigDecimal credit = optional(cell(row, mapping.getCreditColumn()));
      if (debit == null && credit == null) {
        return null;
      }
      amount =
          (credit == null ? BigDecimal.ZERO : credit.abs())
              .subtract(debit == null ? BigDecimal.ZERO : debit.abs());
    }
    return mapping.isInvertSign() ? amount.negate() : amount;
  }

  private static @Nullable BigDecimal optional(String raw) {
    return raw.isEmpty() ? null : Amounts.parse(raw);
  }

  static @Nullable LocalDate parseDate(String raw, String format) {
    if (raw.isBlank()) {
      return null;
    }
    List<String> formats = new ArrayList<>();
    formats.add(format);
    formats.addAll(FALLBACK_DATES);
    for (String f : formats) {
      try {
        var formatter = DateTimeFormatter.ofPattern(f, Locale.ROOT);
        return f.contains("H")
            ? LocalDateTime.parse(raw.strip(), formatter).toLocalDate()
            : LocalDate.parse(raw.strip(), formatter);
      } catch (DateTimeParseException | IllegalArgumentException ignored) {
        // следующий формат
      }
    }
    return null;
  }

  static String cell(List<String> row, @Nullable Integer column) {
    if (column == null || column < 0 || column >= row.size()) {
      return "";
    }
    return row.get(column).strip();
  }

  /**
   * Разобранная строка выписки.
   *
   * @param index номер строки в файле, с нуля
   * @param amount со знаком: «−» — списание
   * @param error почему строку нельзя импортировать
   */
  record ParsedRow(
      int index,
      List<String> raw,
      @Nullable LocalDate date,
      @Nullable BigDecimal amount,
      String description,
      @Nullable BigDecimal originalAmount,
      @Nullable String originalCurrency,
      @Nullable String error) {

    static ParsedRow error(int index, List<String> raw, String error) {
      return new ParsedRow(index, raw, null, null, "", null, null, error);
    }

    boolean ok() {
      return error == null;
    }
  }
}
