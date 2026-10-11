package com.spendings.importing;

import com.spendings.account.AccountRef;
import com.spendings.account.Accounts;
import com.spendings.category.Categories;
import com.spendings.category.CategoryKind;
import com.spendings.importing.CategorizationRules.Rule;
import com.spendings.importing.RowParser.ParsedRow;
import com.spendings.operation.OperationStats;
import com.spendings.operation.OperationStats.DatedAmount;
import com.spendings.operation.Operations;
import com.spendings.operation.RecordOperation;
import com.spendings.operation.RecordOperation.RecordExpense;
import com.spendings.operation.RecordOperation.RecordIncome;
import com.spendings.operation.RecordOperation.Split;
import com.spendings.shared.DomainException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Предпросмотр и импорт выписки. Каждая строка получает ключ из счёта, даты, суммы, описания и
 * номера повтора в файле: так повторный импорт того же файла ничего не задваивает, а две одинаковые
 * покупки в один день остаются двумя.
 */
@Service
@Transactional(readOnly = true)
public class Importer {

  private static final int PAYEE_LENGTH = 200;

  private final Accounts accounts;
  private final Categories categories;
  private final Operations operations;
  private final OperationStats stats;
  private final CategorizationRules rules;
  private final JdbcClient jdbc;

  Importer(
      Accounts accounts,
      Categories categories,
      Operations operations,
      OperationStats stats,
      CategorizationRules rules,
      JdbcClient jdbc) {
    this.accounts = accounts;
    this.categories = categories;
    this.operations = operations;
    this.stats = stats;
    this.rules = rules;
    this.jdbc = jdbc;
  }

  /** Что будет с каждой строкой, если импортировать её на этот счёт. */
  Preview preview(TableFile table, ImportMapping mapping) {
    List<PreviewRow> result = new ArrayList<>();
    if (mapping.getAccountId() == null || mapping.getDateColumn() == null || !mapping.hasAmount()) {
      return new Preview(result, null);
    }
    AccountRef account = accounts.get(mapping.getAccountId());
    List<ParsedRow> parsed = RowParser.parse(table, mapping);

    Map<String, Integer> repeats = new HashMap<>();
    List<String> keys = new ArrayList<>();
    for (ParsedRow row : parsed) {
      if (row.ok()) {
        String identity =
            account.id()
                + "|"
                + row.date()
                + "|"
                + row.amount().stripTrailingZeros().toPlainString()
                + "|"
                + CategorizationRules.normalize(row.description());
        int n = repeats.merge(identity, 1, Integer::sum);
        keys.add(sha256(identity + "|" + n));
      } else {
        keys.add("");
      }
    }
    Set<String> known =
        stats.existingExternalKeys(keys.stream().filter(k -> !k.isEmpty()).toList());

    LocalDate min =
        parsed.stream()
            .filter(ParsedRow::ok)
            .map(ParsedRow::date)
            .min(LocalDate::compareTo)
            .orElse(LocalDate.now());
    LocalDate max =
        parsed.stream()
            .filter(ParsedRow::ok)
            .map(ParsedRow::date)
            .max(LocalDate::compareTo)
            .orElse(LocalDate.now());
    List<DatedAmount> manual =
        new ArrayList<>(stats.manualAmounts(account.id(), min.minusDays(1), max.plusDays(1)));
    List<Rule> allRules = rules.all();

    for (int i = 0; i < parsed.size(); i++) {
      ParsedRow row = parsed.get(i);
      String key = keys.get(i);
      if (!row.ok()) {
        result.add(new PreviewRow(row, key, Status.ERROR, null, null));
        continue;
      }
      CategoryKind kind = row.amount().signum() < 0 ? CategoryKind.EXPENSE : CategoryKind.INCOME;
      UUID ruleCategory = CategorizationRules.match(allRules, row.description(), kind).orElse(null);
      Status status;
      if (known.contains(key)) {
        status = Status.ALREADY_IMPORTED;
      } else if (takeManualTwin(manual, row)) {
        status = Status.PROBABLE_DUPLICATE;
      } else {
        status = Status.NEW;
      }
      result.add(new PreviewRow(row, key, status, kind, ruleCategory));
    }
    return new Preview(result, account);
  }

  /** Импортирует новые строки (и вероятные дубли, если попросили) во «Входящие». */
  @Transactional
  public Result commit(
      TableFile table, ImportMapping mapping, String fileName, @Nullable UUID profileId) {
    Preview preview = preview(table, mapping);
    if (preview.account() == null) {
      throw new DomainException("import.error.mappingIncomplete");
    }
    AccountRef account = preview.account();
    UUID batchId =
        jdbc.sql(
                "insert into import_batch (account_id, profile_id, file_name, rows_total) values (?, ?, ?, ?) returning id")
            .params(account.id(), profileId, fileName, preview.rows().size())
            .query(UUID.class)
            .single();

    int imported = 0;
    Map<CategoryKind, UUID> fallback = new HashMap<>();
    for (PreviewRow row : preview.rows()) {
      boolean take =
          row.status() == Status.NEW
              || (row.status() == Status.PROBABLE_DUPLICATE
                  && mapping.isIncludeProbableDuplicates());
      if (!take) {
        continue;
      }
      UUID category =
          row.categoryId() != null
              ? row.categoryId()
              : fallback.computeIfAbsent(row.kind(), categories::uncategorized);
      operations.recordImported(command(account, row, category), batchId, row.key());
      imported++;
    }
    int skipped = preview.rows().size() - imported;
    jdbc.sql("update import_batch set rows_imported = ?, rows_skipped = ? where id = ?")
        .params(imported, skipped, batchId)
        .update();
    return new Result(batchId, imported, skipped);
  }

  /** Откатывает партию целиком: её операции удаляются, даже уже подтверждённые. */
  @Transactional
  public int undo(UUID batchId) {
    int deleted = operations.deleteBatch(batchId);
    jdbc.sql("delete from import_batch where id = ?").param(batchId).update();
    return deleted;
  }

  List<Batch> batches() {
    return jdbc.sql(
            """
            select b.id, b.file_name, b.imported_at, b.rows_imported, b.rows_skipped, a.name as account,
                   (select count(*) from operation o where o.import_id = b.id and o.status = 'REVIEW') as in_review
            from import_batch b join account a on a.id = b.account_id
            order by b.imported_at desc
            limit 20
            """)
        .query(
            (rs, i) ->
                new Batch(
                    rs.getObject("id", UUID.class),
                    rs.getString("file_name"),
                    rs.getTimestamp("imported_at").toInstant(),
                    rs.getString("account"),
                    rs.getInt("rows_imported"),
                    rs.getInt("rows_skipped"),
                    rs.getInt("in_review")))
        .list();
  }

  private static RecordOperation command(AccountRef account, PreviewRow row, UUID category) {
    ParsedRow p = row.row();
    String payee = p.description().isEmpty() ? null : truncate(p.description());
    BigDecimal amount = p.amount().abs();
    BigDecimal originalAmount =
        p.originalCurrency() != null && !p.originalCurrency().equals(account.currency())
            ? p.originalAmount()
            : null;
    Split split =
        new Split(
            category,
            amount,
            originalAmount,
            originalAmount == null ? null : p.originalCurrency(),
            null);
    return row.kind() == CategoryKind.EXPENSE
        ? new RecordExpense(account.id(), p.date(), List.of(split), payee, null)
        : new RecordIncome(account.id(), p.date(), List.of(split), payee, null);
  }

  /** Есть ли ручная операция с той же суммой ±1 день; найденная больше не участвует. */
  private static boolean takeManualTwin(List<DatedAmount> manual, ParsedRow row) {
    for (int i = 0; i < manual.size(); i++) {
      DatedAmount m = manual.get(i);
      long days = Math.abs(m.date().toEpochDay() - row.date().toEpochDay());
      if (days <= 1 && m.amount().compareTo(row.amount()) == 0) {
        manual.remove(i);
        return true;
      }
    }
    return false;
  }

  private static String truncate(String text) {
    return text.length() <= PAYEE_LENGTH ? text : text.substring(0, PAYEE_LENGTH);
  }

  static String sha256(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  enum Status {
    NEW,
    PROBABLE_DUPLICATE,
    ALREADY_IMPORTED,
    ERROR
  }

  /**
   * @param account {@code null}, пока не выбраны счёт, дата и сумма
   */
  record Preview(List<PreviewRow> rows, @Nullable AccountRef account) {

    long count(Status status) {
      return rows.stream().filter(r -> r.status() == status).count();
    }

    public long newRows() {
      return count(Status.NEW);
    }

    public long probableDuplicates() {
      return count(Status.PROBABLE_DUPLICATE);
    }

    public long alreadyImported() {
      return count(Status.ALREADY_IMPORTED);
    }

    public long errors() {
      return count(Status.ERROR);
    }
  }

  record PreviewRow(
      ParsedRow row,
      String key,
      Status status,
      @Nullable CategoryKind kind,
      @Nullable UUID categoryId) {}

  public record Result(UUID batchId, int imported, int skipped) {}

  record Batch(
      UUID id,
      String fileName,
      java.time.Instant importedAt,
      String account,
      int imported,
      int skipped,
      int inReview) {}
}
