package com.spendings.backup;

import com.spendings.shared.DomainException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Бэкап всех данных в JSON. Значения хранятся строками в текстовом виде PostgreSQL, поэтому формат
 * не зависит от Java-классов: при восстановлении тип берётся из текущей схемы, а колонки, которых в
 * схеме уже нет, пропускаются. Учётная запись в бэкап не входит.
 */
@Service
public class Backup {

  static final String FORMAT = "spendings-backup";
  static final int VERSION = 1;

  /** Порядок важен: сначала то, на что ссылаются. */
  static final List<String> TABLES =
      List.of(
          "currency",
          "app_settings",
          "account",
          "deposit_terms",
          "category",
          "import_profile",
          "import_batch",
          "operation",
          "operation_line",
          "goal",
          "goal_account",
          "goal_contribution",
          "categorization_rule",
          "exchange_rate");

  private final JdbcClient jdbc;
  private final JsonMapper json;

  Backup(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Transactional(readOnly = true)
  public void export(OutputStream out) {
    Map<String, Object> document = new LinkedHashMap<>();
    document.put("format", FORMAT);
    document.put("version", VERSION);
    document.put("exportedAt", Instant.now().toString());
    document.put(
        "schema",
        jdbc.sql(
                "select version from flyway_schema_history where success order by installed_rank desc limit 1")
            .query(String.class)
            .optional()
            .orElse("?"));
    Map<String, List<Map<String, String>>> tables = new LinkedHashMap<>();
    for (String table : TABLES) {
      tables.put(table, rows(table));
    }
    document.put("tables", tables);
    json.writeValue(out, document);
  }

  /** Восстанавливает бэкап в установку, где ещё нет ни счетов, ни операций, ни целей. */
  @Transactional
  public Summary restore(InputStream in) throws IOException {
    Map<String, Object> document = json.readValue(in, new TypeReference<>() {});
    if (!FORMAT.equals(document.get("format"))) {
      throw new DomainException("backup.error.notABackup");
    }
    if (!isEmpty()) {
      throw new DomainException("backup.error.notEmpty");
    }
    @SuppressWarnings("unchecked")
    Map<String, List<Map<String, String>>> tables =
        (Map<String, List<Map<String, String>>>) document.get("tables");
    if (tables == null) {
      throw new DomainException("backup.error.notABackup");
    }

    // шаблонные категории и курсы заменяются тем, что в бэкапе
    jdbc.sql("delete from categorization_rule").update();
    jdbc.sql("update category set parent_id = null").update();
    jdbc.sql("delete from category").update();
    jdbc.sql("delete from exchange_rate").update();
    jdbc.sql("delete from import_profile").update();

    int rows = 0;
    for (String table : TABLES) {
      List<Map<String, String>> data = tables.getOrDefault(table, List.of());
      if (table.equals("app_settings")) {
        data.stream()
            .findFirst()
            .map(r -> r.get("base_currency"))
            .ifPresent(
                code -> jdbc.sql("update app_settings set base_currency = ?").param(code).update());
        continue;
      }
      if (table.equals("category")) {
        data = parentsFirst(data);
      }
      Map<String, String> types = columnTypes(table);
      for (Map<String, String> row : data) {
        rows += insert(table, row, types);
      }
    }
    return new Summary(rows, String.valueOf(document.get("exportedAt")));
  }

  boolean isEmpty() {
    return jdbc.sql(
            "select not exists(select 1 from account) and not exists(select 1 from operation)"
                + " and not exists(select 1 from goal)")
        .query(Boolean.class)
        .single();
  }

  private List<Map<String, String>> rows(String table) {
    List<String> columns = new ArrayList<>(columnTypes(table).keySet());
    String select =
        columns.stream()
            .map(c -> "\"" + c + "\"::text as \"" + c + "\"")
            .reduce((a, b) -> a + ", " + b)
            .orElseThrow();
    return jdbc.sql("select " + select + " from " + table)
        .query(
            (rs, i) -> {
              Map<String, String> row = new LinkedHashMap<>();
              for (String column : columns) {
                row.put(column, rs.getString(column));
              }
              return row;
            })
        .list();
  }

  private int insert(String table, Map<String, String> row, Map<String, String> types) {
    List<String> columns = row.keySet().stream().filter(types::containsKey).toList();
    if (columns.isEmpty()) {
      return 0;
    }
    String names = String.join(", ", columns.stream().map(c -> "\"" + c + "\"").toList());
    String values =
        String.join(
            ", ",
            columns.stream().map(c -> "cast(:" + param(c) + " as " + types.get(c) + ")").toList());
    var spec =
        jdbc.sql(
            "insert into "
                + table
                + " ("
                + names
                + ") values ("
                + values
                + ") on conflict do nothing");
    for (String column : columns) {
      spec = spec.param(param(column), row.get(column));
    }
    return spec.update();
  }

  /** Колонки таблицы и их типы — только из текущей схемы, поэтому имена из файла безопасны. */
  private Map<String, String> columnTypes(String table) {
    Map<String, String> types = new LinkedHashMap<>();
    jdbc.sql(
            """
            select column_name, case when data_type = 'USER-DEFINED' then udt_name
                                     when data_type = 'character' then 'char(' || character_maximum_length || ')'
                                     else data_type end as type
            from information_schema.columns
            where table_schema = current_schema() and table_name = ?
            order by ordinal_position
            """)
        .param(table)
        .query((rs, i) -> types.put(rs.getString("column_name"), rs.getString("type")))
        .list();
    return types;
  }

  private static List<Map<String, String>> parentsFirst(List<Map<String, String>> rows) {
    List<Map<String, String>> sorted =
        new ArrayList<>(rows.stream().filter(r -> r.get("parent_id") == null).toList());
    sorted.addAll(rows.stream().filter(r -> r.get("parent_id") != null).toList());
    return sorted;
  }

  private static String param(String column) {
    return "p_" + column;
  }

  public record Summary(int rows, String exportedAt) {}
}
