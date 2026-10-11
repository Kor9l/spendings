package com.spendings.importing;

import com.spendings.category.CategoriesMerged;
import com.spendings.category.CategoryKind;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Правила «описание содержит текст → категория». Если подходят несколько, побеждает самое длинное
 * (самое конкретное): «Евроопт Минск» важнее, чем «Евроопт».
 */
@Service
@Transactional(readOnly = true)
public class CategorizationRules {

  static final int MAX_PATTERN = 100;

  private final JdbcClient jdbc;

  CategorizationRules(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  List<Rule> all() {
    return jdbc.sql(
            "select id, pattern, category_id, kind from categorization_rule order by kind, lower(pattern)")
        .query(
            (rs, i) ->
                new Rule(
                    rs.getObject("id", UUID.class),
                    rs.getString("pattern"),
                    rs.getObject("category_id", UUID.class),
                    CategoryKind.valueOf(rs.getString("kind"))))
        .list();
  }

  /** Категория для описания операции по правилам этого вида. */
  static Optional<UUID> match(List<Rule> rules, String description, CategoryKind kind) {
    String text = normalize(description);
    if (text.isEmpty()) {
      return Optional.empty();
    }
    return rules.stream()
        .filter(r -> r.kind() == kind && text.contains(normalize(r.pattern())))
        .max(Comparator.comparingInt(r -> r.pattern().length()))
        .map(Rule::categoryId);
  }

  /** Создаёт правило или переназначает существующее с тем же текстом. */
  @Transactional
  public void learn(String pattern, UUID categoryId, CategoryKind kind) {
    String clean = pattern.strip();
    if (clean.isEmpty()) {
      return;
    }
    if (clean.length() > MAX_PATTERN) {
      clean = clean.substring(0, MAX_PATTERN);
    }
    jdbc.sql(
            """
            insert into categorization_rule (pattern, category_id, kind) values (:pattern, :category, :kind)
            on conflict (lower(pattern), kind) do update set category_id = excluded.category_id
            """)
        .param("pattern", clean)
        .param("category", categoryId)
        .param("kind", kind.name())
        .update();
  }

  @Transactional
  void delete(UUID id) {
    jdbc.sql("delete from categorization_rule where id = ?").param(id).update();
  }

  /** Правила объединённой категории переходят к целевой. */
  @EventListener
  void on(CategoriesMerged merged) {
    jdbc.sql("update categorization_rule set category_id = :target where category_id = :source")
        .param("target", merged.target())
        .param("source", merged.source())
        .update();
  }

  static String normalize(String text) {
    return text.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("\\s+", " ").strip();
  }

  record Rule(UUID id, String pattern, UUID categoryId, CategoryKind kind) {}
}
