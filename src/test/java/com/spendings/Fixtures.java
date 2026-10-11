package com.spendings;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Тестовые данные напрямую в базу: так тесты одного модуля не зависят от API другого. */
@Component
public class Fixtures {

  private final JdbcClient jdbc;
  private final EntityManager entityManager;

  Fixtures(JdbcClient jdbc, EntityManager entityManager) {
    this.entityManager = entityManager;
    this.jdbc = jdbc;
  }

  public UUID account(String name, String currency, String openingBalance) {
    return jdbc.sql(
            "insert into account (name, type, currency, opening_balance) values (?, 'CARD', ?, ?)"
                + " returning id")
        .params(name, currency, new BigDecimal(openingBalance))
        .query(UUID.class)
        .single();
  }

  public void archiveAccount(UUID id) {
    // JPA в той же транзакции мог уже загрузить счёт: сбросить изменения, обновить, перечитать
    entityManager.flush();
    jdbc.sql("update account set archived_at = now() where id = ?").param(id).update();
    entityManager.clear();
  }

  /** Категория из шаблона: {@code category("Еда", "Продукты")} или {@code category("Еда")}. */
  public UUID category(String... path) {
    if (path.length == 1) {
      return jdbc.sql("select id from category where parent_id is null and name = ?")
          .param(path[0])
          .query(UUID.class)
          .single();
    }
    return jdbc.sql(
            "select c.id from category c join category p on p.id = c.parent_id"
                + " where p.name = ? and c.name = ?")
        .params(path[0], path[1])
        .query(UUID.class)
        .single();
  }

  public UUID incomeCategory(String name) {
    return jdbc.sql(
            "select id from category where kind = 'INCOME' and parent_id is null and name = ?")
        .param(name)
        .query(UUID.class)
        .single();
  }

  public int linesWithCategory(UUID categoryId) {
    return jdbc.sql("select count(*) from operation_line where category_id = ?")
        .param(categoryId)
        .query(Integer.class)
        .single();
  }
}
