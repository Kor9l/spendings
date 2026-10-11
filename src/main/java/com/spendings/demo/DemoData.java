package com.spendings.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Демонстрационные данные: три счёта и {@code n} операций за три года — расходы по всем конечным
 * категориям шаблона, зарплата дважды в месяц, переводы на накопления. Для проверки скорости и для
 * показа. Пишет напрямую в базу одним SQL — 50 тысяч операций за секунды.
 */
@Service
public class DemoData {

  private static final Logger log = LoggerFactory.getLogger(DemoData.class);
  static final int YEARS = 3;

  private final JdbcClient jdbc;

  DemoData(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Добавляет данные, если в базе ещё нет операций. Возвращает, сколько операций создано. */
  @Transactional
  public int generate(int operations) {
    boolean empty =
        jdbc.sql("select not exists(select 1 from operation)").query(Boolean.class).single();
    if (!empty) {
      log.info("Демо-данные не созданы: в базе уже есть операции");
      return 0;
    }
    long started = System.nanoTime();
    LocalAccounts a = accounts();
    int days = YEARS * 365;

    // зарплата: 5-го и 20-го числа каждого месяца, 2 операции в месяц
    int paydays = 2 * (YEARS * 12 + 1);
    int expenses = Math.max(0, operations - paydays);

    // расходы: случайные дата, конечная категория, счёт, сумма и получатель — на каждую строку
    jdbc.sql(
            """
            with leaf as (
              select array_agg(c.id) as ids from category c
              where c.kind = 'EXPENSE' and c.archived_at is null
                and not exists (select 1 from category s where s.parent_id = c.id)
            ),
            draft as (
              select current_date - (random() * :days)::int as day,
                     leaf.ids[1 + floor(random() * cardinality(leaf.ids))::int] as category,
                     case when random() < 0.25 then :cash else :card end as account,
                     round((3 + random() * random() * 180)::numeric, 2) as amount,
                     (array['Евроопт', 'Green', 'Соседи', 'Белоруснефть', 'Wildberries', 'Ozon',
                            'Корона', 'Аптека', 'Кафе', 'Такси'])[1 + floor(random() * 10)::int] as payee,
                     g as n
              from generate_series(1, :count) g cross join leaf
            ),
            ops as (
              insert into operation (type, occurred_on, payee, note)
              select 'EXPENSE', day, payee, 'demo:' || n from draft
              returning id, note
            )
            insert into operation_line (operation_id, account_id, category_id, amount)
            select o.id, d.account, d.category, -d.amount
            from ops o join draft d on 'demo:' || d.n = o.note
            """)
        .param("days", days)
        .param("cash", a.cash())
        .param("card", a.card())
        .param("count", expenses)
        .update();

    // зарплата покрывает средние расходы месяца и оставляет ~15 % на сбережения
    int salaries =
        jdbc.sql(
                """
                with spend as (
                  select coalesce(-sum(amount), 0) / (:years * 12) as monthly from operation_line
                ),
                months as (
                  select d::date as day from generate_series(
                    date_trunc('month', current_date) - make_interval(years => :years),
                    current_date, interval '1 month') d
                ),
                paydays as (
                  select day + 4 as day, 0.45 as share from months
                  union all select day + 19, 0.55 from months
                ),
                ops as (
                  insert into operation (type, occurred_on, payee)
                  select 'INCOME', day, 'ООО «Работа»' from paydays where day <= current_date
                  returning id, occurred_on
                )
                insert into operation_line (operation_id, account_id, category_id, amount)
                select o.id, :card, :salary, round(spend.monthly * 1.15 * p.share, 2)
                from ops o join paydays p on p.day = o.occurred_on cross join spend
                """)
            .param("years", YEARS)
            .param("card", a.card())
            .param("salary", a.salaryCategory())
            .update();

    jdbc.sql("update operation set note = null where note like 'demo:%'").update();

    // остатки: начальный остаток покрывает расходы, чтобы счета не уходили в минус
    jdbc.sql(
            """
            update account a set opening_balance = greatest(0, -coalesce((
              select sum(l.amount) from operation_line l where l.account_id = a.id), 0)) + 500
            where a.id in (:card, :cash)
            """)
        .param("card", a.card())
        .param("cash", a.cash())
        .update();

    int total = salaries + expenses;
    log.info("Демо-данные: {} операций за {} мс", total, (System.nanoTime() - started) / 1_000_000);
    jdbc.sql("analyze operation").update();
    jdbc.sql("analyze operation_line").update();
    return total;
  }

  private LocalAccounts accounts() {
    java.time.LocalDate since = java.time.LocalDate.now().minusYears(YEARS).withDayOfMonth(1);
    var insert =
        "insert into account (name, type, currency, opening_balance, opened_on, sort_order)"
            + " values (?, ?, ?, ?, ?, ?) returning id";
    var card =
        jdbc.sql(insert)
            .params("Карта (демо)", "CARD", "BYN", 0, since, 1)
            .query(java.util.UUID.class)
            .single();
    var cash =
        jdbc.sql(insert)
            .params("Наличные (демо)", "CASH", "BYN", 0, since, 2)
            .query(java.util.UUID.class)
            .single();
    jdbc.sql(insert)
        .params("Сбережения USD (демо)", "SAVINGS", "USD", 3000, since, 3)
        .query(java.util.UUID.class)
        .single();
    var salary =
        jdbc.sql(
                "select id from category where kind = 'INCOME' and parent_id is null"
                    + " order by sort_order limit 1")
            .query(java.util.UUID.class)
            .single();
    return new LocalAccounts(card, cash, salary);
  }

  private record LocalAccounts(
      java.util.UUID card, java.util.UUID cash, java.util.UUID salaryCategory) {}
}
