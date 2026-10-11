package com.spendings.operation;

import com.spendings.account.AccountMovements;
import com.spendings.category.CategoriesMerged;
import com.spendings.category.CategoryUsage;
import com.spendings.fx.RateDemand;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * То, что модулям счетов и категорий нужно знать об операциях. Зависит только от базы — иначе
 * получился бы цикл бинов {@code Accounts → AccountMovements → Accounts}.
 */
@Component
class LedgerTotals implements AccountMovements, CategoryUsage, RateDemand {

  private final JdbcClient jdbc;

  LedgerTotals(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Map<UUID, BigDecimal> totals() {
    var totals = new HashMap<UUID, BigDecimal>();
    jdbc.sql(
            """
            select l.account_id, sum(l.amount) as total
            from operation_line l
            join operation o on o.id = l.operation_id
            where o.status = 'CLEARED'
            group by l.account_id
            """)
        .query(
            (rs, n) ->
                totals.put(rs.getObject("account_id", UUID.class), rs.getBigDecimal("total")))
        .list();
    return totals;
  }

  @Override
  public boolean hasMovements(UUID accountId) {
    return jdbc.sql("select exists(select 1 from operation_line where account_id = ?)")
        .param(accountId)
        .query(Boolean.class)
        .single();
  }

  @Override
  public NavigableMap<LocalDate, BigDecimal> dailyChanges(UUID accountId) {
    var changes = new TreeMap<LocalDate, BigDecimal>();
    jdbc.sql(
            """
            select o.occurred_on, sum(l.amount) as total
            from operation_line l
            join operation o on o.id = l.operation_id
            where o.status = 'CLEARED' and l.account_id = ?
            group by o.occurred_on
            """)
        .param(accountId)
        .query(
            (rs, n) ->
                changes.put(
                    rs.getObject("occurred_on", LocalDate.class), rs.getBigDecimal("total")))
        .list();
    return changes;
  }

  @Override
  public boolean isUsed(UUID categoryId) {
    return jdbc.sql("select exists(select 1 from operation_line where category_id = ?)")
        .param(categoryId)
        .query(Boolean.class)
        .single();
  }

  @Override
  public Optional<LocalDate> earliestDate() {
    return jdbc.sql("select min(occurred_on) from operation").query(LocalDate.class).optional();
  }

  /** Синхронно, в транзакции объединения: до того, как исходная категория будет удалена. */
  @EventListener
  void on(CategoriesMerged merged) {
    jdbc.sql("update operation_line set category_id = :target where category_id = :source")
        .param("target", merged.target())
        .param("source", merged.source())
        .update();
  }
}
