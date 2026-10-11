package com.spendings.operation;

import com.spendings.operation.OperationFeed.ExportRow;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Выгрузка операций. CSV — для Excel с русской локалью: UTF-8 с BOM, разделитель «;», десятичная
 * запятая. XLSX — с настоящими числами и датами.
 */
@Component
class OperationExport {

  /** Byte order mark: по нему Excel понимает, что CSV в UTF-8. */
  private static final char BOM = (char) 0xFEFF;

  static final String[] HEADER = {
    "Дата",
    "Тип",
    "Счёт",
    "Валюта",
    "Сумма",
    "Категория",
    "Подкатегория",
    "Получатель",
    "Заметка",
    "Сумма покупки",
    "Валюта покупки"
  };

  void csv(List<ExportRow> rows, OutputStream out) throws IOException {
    Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
    writer.write(BOM);
    writer.write(String.join(";", HEADER));
    writer.write("\r\n");
    for (ExportRow r : rows) {
      String[] cells = {
        r.date().toString(),
        typeLabel(r.type()),
        r.account(),
        r.currency(),
        decimal(r.amount()),
        r.category(),
        r.subcategory(),
        r.payee(),
        r.note(),
        decimal(r.originalAmount()),
        r.originalCurrency()
      };
      for (int i = 0; i < cells.length; i++) {
        if (i > 0) {
          writer.write(';');
        }
        writer.write(csvCell(cells[i]));
      }
      writer.write("\r\n");
    }
    writer.flush();
  }

  void xlsx(List<ExportRow> rows, OutputStream out) throws IOException {
    Workbook workbook = new Workbook(out, "Spendings", "1.0");
    Worksheet sheet = workbook.newWorksheet("Операции");
    for (int c = 0; c < HEADER.length; c++) {
      sheet.value(0, c, HEADER[c]);
      sheet.style(0, c).bold().set();
    }
    int row = 1;
    for (ExportRow r : rows) {
      sheet.value(row, 0, r.date());
      sheet.style(row, 0).format("dd.mm.yyyy").set();
      sheet.value(row, 1, typeLabel(r.type()));
      sheet.value(row, 2, r.account());
      sheet.value(row, 3, r.currency());
      sheet.value(row, 4, r.amount());
      sheet.style(row, 4).format("#,##0.00").set();
      text(sheet, row, 5, r.category());
      text(sheet, row, 6, r.subcategory());
      text(sheet, row, 7, r.payee());
      text(sheet, row, 8, r.note());
      if (r.originalAmount() != null) {
        sheet.value(row, 9, r.originalAmount());
        sheet.style(row, 9).format("#,##0.00").set();
      }
      text(sheet, row, 10, r.originalCurrency());
      row++;
    }
    sheet.freezePane(0, 1);
    workbook.finish();
  }

  private static void text(Worksheet sheet, int row, int column, @Nullable String value) {
    if (value != null) {
      sheet.value(row, column, value);
    }
  }

  static String typeLabel(OperationType type) {
    return switch (type) {
      case EXPENSE -> "Расход";
      case INCOME -> "Доход";
      case TRANSFER -> "Перевод";
      case ADJUSTMENT -> "Корректировка";
    };
  }

  private static String decimal(@Nullable BigDecimal value) {
    return value == null ? "" : value.stripTrailingZeros().toPlainString().replace('.', ',');
  }

  static String csvCell(@Nullable String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    // формулы в ячейках CSV опасны при открытии в Excel — экранируем
    String safe =
        "=+-@".indexOf(value.charAt(0)) >= 0 && !value.matches("-?\\d+(,\\d+)?")
            ? "'" + value
            : value;
    if (safe.contains(";") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
      return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
    return safe;
  }
}
