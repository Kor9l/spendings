package com.spendings.importing;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.dhatim.fastexcel.reader.Cell;
import org.dhatim.fastexcel.reader.CellType;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.ReadingOptions;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;
import org.jspecify.annotations.Nullable;

/**
 * Файл выписки как таблица строк. CSV: кодировка (UTF-8 или windows-1251) и разделитель
 * определяются сами, если их не задали; кавычки — по RFC 4180. XLSX: первый лист, даты — в виде
 * {@code yyyy-MM-dd}.
 */
record TableFile(List<List<String>> rows, String encoding, char delimiter, boolean excel) {

  static final Charset WINDOWS_1251 = Charset.forName("windows-1251");
  private static final char[] DELIMITERS = {';', ',', '\t', '|'};

  static TableFile read(
      String fileName, byte[] content, @Nullable String encoding, @Nullable Character delimiter) {
    if (isXlsx(fileName, content)) {
      return new TableFile(readXlsx(content), "UTF-8", ';', true);
    }
    String charset = encoding != null ? encoding : detectEncoding(content);
    String text = new String(content, Charset.forName(charset));
    if (!text.isEmpty() && text.charAt(0) == '﻿') {
      text = text.substring(1);
    }
    char sep = delimiter != null ? delimiter : detectDelimiter(text);
    return new TableFile(parseCsv(text, sep), charset, sep, false);
  }

  int columns() {
    return rows.stream().mapToInt(List::size).max().orElse(0);
  }

  static boolean isXlsx(String fileName, byte[] content) {
    return fileName.toLowerCase(Locale.ROOT).endsWith(".xlsx")
        || (content.length > 2 && content[0] == 'P' && content[1] == 'K');
  }

  /** UTF-8, если файл им корректно декодируется; иначе — windows-1251, как у банков СНГ. */
  static String detectEncoding(byte[] content) {
    try {
      StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(content));
      return "UTF-8";
    } catch (CharacterCodingException e) {
      return "windows-1251";
    }
  }

  /** Разделитель, который встречается в первых строках чаще и стабильнее остальных. */
  static char detectDelimiter(String text) {
    List<String> lines = text.lines().filter(l -> !l.isBlank()).limit(20).toList();
    char best = ';';
    int bestScore = -1;
    for (char candidate : DELIMITERS) {
      int score = 0;
      for (String line : lines) {
        if (parseLine(line, candidate).size() > 1) {
          score++;
        }
      }
      if (score > bestScore) {
        best = candidate;
        bestScore = score;
      }
    }
    return best;
  }

  static List<List<String>> parseCsv(String text, char delimiter) {
    List<List<String>> rows = new ArrayList<>();
    List<String> row = new ArrayList<>();
    var cell = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (quoted) {
        if (c == '"') {
          if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
            cell.append('"');
            i++;
          } else {
            quoted = false;
          }
        } else {
          cell.append(c);
        }
      } else if (c == '"' && cell.isEmpty()) {
        quoted = true;
      } else if (c == delimiter) {
        row.add(cell.toString().strip());
        cell.setLength(0);
      } else if (c == '\n' || c == '\r') {
        if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
          i++;
        }
        row.add(cell.toString().strip());
        cell.setLength(0);
        addRow(rows, row);
        row = new ArrayList<>();
      } else {
        cell.append(c);
      }
    }
    if (!cell.isEmpty() || !row.isEmpty()) {
      row.add(cell.toString().strip());
      addRow(rows, row);
    }
    return rows;
  }

  private static List<String> parseLine(String line, char delimiter) {
    List<List<String>> parsed = parseCsv(line, delimiter);
    return parsed.isEmpty() ? List.of() : parsed.getFirst();
  }

  private static void addRow(List<List<String>> rows, List<String> row) {
    if (row.stream().anyMatch(c -> !c.isEmpty())) {
      rows.add(row);
    }
  }

  private static List<List<String>> readXlsx(byte[] content) {
    try (var workbook =
        new ReadableWorkbook(new ByteArrayInputStream(content), new ReadingOptions(true, false))) {
      Sheet sheet = workbook.getFirstSheet();
      List<List<String>> rows = new ArrayList<>();
      try (var stream = sheet.openStream()) {
        stream.forEach(
            (Row r) -> {
              List<String> cells = new ArrayList<>();
              for (int i = 0; i < r.getCellCount(); i++) {
                cells.add(text(r.getCell(i)));
              }
              addRow(rows, cells);
            });
      }
      return rows;
    } catch (IOException e) {
      throw new UncheckedIOException("Не удалось прочитать XLSX", e);
    }
  }

  private static String text(@Nullable Cell cell) {
    if (cell == null || cell.getType() == CellType.EMPTY) {
      return "";
    }
    if (cell.getType() == CellType.NUMBER && looksLikeDate(cell.getDataFormatString())) {
      LocalDateTime dateTime = cell.asDate();
      return dateTime.toLocalDate().toString();
    }
    if (cell.getType() == CellType.NUMBER) {
      return cell.asNumber().stripTrailingZeros().toPlainString();
    }
    String raw = cell.getText();
    return raw == null ? "" : raw.strip();
  }

  private static boolean looksLikeDate(@Nullable String format) {
    if (format == null) {
      return false;
    }
    String f = format.toLowerCase(Locale.ROOT);
    return (f.contains("d") && f.contains("m")) || f.contains("yy");
  }
}
