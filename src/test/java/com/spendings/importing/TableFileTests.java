package com.spendings.importing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.junit.jupiter.api.Test;

class TableFileTests {

  /** Как выгружают белорусские банки: windows-1251, «;», шапка перед таблицей. */
  static final String BANK_CSV =
      """
      Выписка по счёту;;;
      Карта: **** 1234;;;
      Дата транзакции;Операция;Сумма;Валюта
      03.10.2026 14:22:01;"Оплата: EUROOPT, MINSK";-62,10;BYN
      03.10.2026 18:05:12;Оплата: KOFEINIA "ZERNO";-6,50;BYN
      05.10.2026 09:00:00;Зачисление зарплаты;1 400,00;BYN
      05.10.2026 12:00:00;Оплата: AMAZON;-65,02;BYN
      """;

  @Test
  void detectsWindows1251AndSemicolonAndHeader() {
    TableFile table =
        TableFile.read("vypiska.csv", BANK_CSV.getBytes(TableFile.WINDOWS_1251), null, null);

    assertThat(table.encoding()).isEqualTo("windows-1251");
    assertThat(table.delimiter()).isEqualTo(';');
    assertThat(table.rows().get(3))
        .containsExactly("03.10.2026 14:22:01", "Оплата: EUROOPT, MINSK", "-62,10", "BYN");
    assertThat(table.rows().get(4).get(1)).isEqualTo("Оплата: KOFEINIA \"ZERNO\"");

    ImportMapping mapping = ImportMapping.guess(table);
    assertThat(mapping.getHeaderRow()).isEqualTo(2);
    assertThat(mapping.getDateColumn()).isZero();
    assertThat(mapping.getDescriptionColumn()).isEqualTo(1);
    assertThat(mapping.getAmountColumn()).isEqualTo(2);
  }

  @Test
  void utf8WithBomAndCommasAndQuotedNewlines() {
    String csv = "﻿Date,Description,Amount\n2026-10-03,\"Two\nlines\",-5.00\n";
    TableFile table = TableFile.read("bank.csv", csv.getBytes(StandardCharsets.UTF_8), null, null);

    assertThat(table.encoding()).isEqualTo("UTF-8");
    assertThat(table.delimiter()).isEqualTo(',');
    assertThat(table.rows()).hasSize(2);
    assertThat(table.rows().get(1).get(1)).isEqualTo("Two\nlines");
  }

  @Test
  void parsesDatesAmountsAndDebitCreditColumns() {
    TableFile table =
        TableFile.read("vypiska.csv", BANK_CSV.getBytes(TableFile.WINDOWS_1251), null, null);
    List<RowParser.ParsedRow> rows = RowParser.parse(table, ImportMapping.guess(table));

    assertThat(rows).hasSize(4).allMatch(RowParser.ParsedRow::ok);
    assertThat(rows.getFirst().date()).isEqualTo(LocalDate.of(2026, 10, 3));
    assertThat(rows.get(2).amount()).isEqualByComparingTo("1400");

    var mapping = new ImportMapping();
    mapping.setDateColumn(0);
    mapping.setDebitColumn(1);
    mapping.setCreditColumn(2);
    RowParser.ParsedRow debit = RowParser.parse(0, List.of("2026-10-03", "12,50", ""), mapping);
    RowParser.ParsedRow credit = RowParser.parse(1, List.of("03.10.26", "", "100"), mapping);
    RowParser.ParsedRow broken = RowParser.parse(2, List.of("вчера", "1", ""), mapping);
    assertThat(debit.amount()).isEqualByComparingTo("-12.50");
    assertThat(credit.amount()).isEqualByComparingTo("100");
    assertThat(credit.date()).isEqualTo(LocalDate.of(2026, 10, 3));
    assertThat(broken.ok()).isFalse();
  }

  @Test
  void readsXlsxWithRealDates() throws IOException {
    var out = new ByteArrayOutputStream();
    var workbook = new Workbook(out, "test", "1.0");
    Worksheet sheet = workbook.newWorksheet("Выписка");
    sheet.value(0, 0, "Дата");
    sheet.value(0, 1, "Описание");
    sheet.value(0, 2, "Сумма");
    sheet.value(1, 0, LocalDate.of(2026, 10, 3));
    sheet.style(1, 0).format("dd.mm.yyyy").set();
    sheet.value(1, 1, "Евроопт");
    sheet.value(1, 2, new BigDecimal("-62.10"));
    workbook.finish();

    TableFile table = TableFile.read("vypiska.xlsx", out.toByteArray(), null, null);
    assertThat(table.excel()).isTrue();
    assertThat(table.rows().get(1)).containsExactly("2026-10-03", "Евроопт", "-62.1");

    List<RowParser.ParsedRow> rows = RowParser.parse(table, ImportMapping.guess(table));
    assertThat(rows.getFirst().date()).isEqualTo(LocalDate.of(2026, 10, 3));
    assertThat(rows.getFirst().amount()).isEqualByComparingTo("-62.10");
  }

  @Test
  void longestRuleWins() {
    var food = java.util.UUID.randomUUID();
    var cafe = java.util.UUID.randomUUID();
    var rules =
        List.of(
            new CategorizationRules.Rule(
                java.util.UUID.randomUUID(),
                "евроопт",
                food,
                com.spendings.category.CategoryKind.EXPENSE),
            new CategorizationRules.Rule(
                java.util.UUID.randomUUID(),
                "Евроопт кафе",
                cafe,
                com.spendings.category.CategoryKind.EXPENSE));

    assertThat(
            CategorizationRules.match(
                rules, "Оплата: ЕВРООПТ  кафе", com.spendings.category.CategoryKind.EXPENSE))
        .contains(cafe);
    assertThat(
            CategorizationRules.match(
                rules, "ЕВРООПТ Минск", com.spendings.category.CategoryKind.EXPENSE))
        .contains(food);
    assertThat(
            CategorizationRules.match(rules, "ЕВРООПТ", com.spendings.category.CategoryKind.INCOME))
        .isEmpty();
  }
}
