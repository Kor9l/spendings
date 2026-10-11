package com.spendings.operation;

import com.spendings.account.AccountRef;
import com.spendings.account.Accounts;
import com.spendings.category.Categories;
import com.spendings.category.CategoryKind;
import com.spendings.category.CategoryRef;
import com.spendings.shared.Amounts;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Чтение операций: лента, выписка счёта, подсказки для ввода. Только SQL, без JPA. */
@Service
@Transactional(readOnly = true)
public class OperationFeed {

  static final int DAYS_PER_PAGE = 10;

  private final JdbcClient jdbc;
  private final Accounts accounts;
  private final Categories categories;

  OperationFeed(JdbcClient jdbc, Accounts accounts, Categories categories) {
    this.jdbc = jdbc;
    this.accounts = accounts;
    this.categories = categories;
  }

  /** Лента: {@value #DAYS_PER_PAGE} последних дней с операциями раньше {@code before}. */
  public Feed page(Feed.Filter filter, @Nullable LocalDate before) {
    return page(filter, before, DAYS_PER_PAGE);
  }

  public Feed page(Feed.Filter filter, @Nullable LocalDate before, int days) {
    var where = new StringBuilder("o.status = 'CLEARED'");
    var params = new HashMap<String, Object>();
    applyFilter(filter, where, params);
    if (before != null) {
      where.append(" and o.occurred_on < :before");
      params.put("before", before);
    }

    params.put("days", days);
    List<LocalDate> dates =
        jdbc.sql(
                "select distinct o.occurred_on from operation o where "
                    + where
                    + " order by o.occurred_on desc limit :days")
            .params(params)
            .query(LocalDate.class)
            .list();
    if (dates.isEmpty()) {
      return new Feed(List.of(), null);
    }

    params.put("newest", dates.getFirst());
    params.put("oldest", dates.getLast());
    List<Row> rows =
        jdbc.sql(
                """
                select o.id, o.type, o.occurred_on, o.payee, o.note,
                       l.account_id, l.category_id, l.amount, l.original_amount, l.original_currency
                from operation o
                join operation_line l on l.operation_id = o.id
                where %s and o.occurred_on between :oldest and :newest
                order by o.occurred_on desc, o.created_at desc, o.id, l.position
                """
                    .formatted(where))
            .params(params)
            .query(OperationFeed::row)
            .list();

    Map<UUID, BigDecimal> balances =
        filter.accountId() == null ? Map.of() : balancesAfter(filter.accountId());
    Feed feed = assemble(rows, filter.accountId(), balances);
    return new Feed(feed.days(), dates.size() == days ? dates.getLast() : null);
  }

  /** Подсказки получателя: недавние, где встречается введённый текст. */
  public List<String> payees(String query) {
    if (query.isBlank()) {
      return List.of();
    }
    return jdbc.sql(
            """
            select payee from operation
            where payee ilike :pattern escape '\\'
            group by payee
            order by max(occurred_on) desc
            limit 8
            """)
        .param("pattern", "%" + escapeLike(query.strip()) + "%")
        .query(String.class)
        .list();
  }

  /** Категория, которую получатель получил в последний раз: подставляется при вводе. */
  public Optional<UUID> lastCategoryFor(String payee, OperationType type) {
    return jdbc.sql(
            """
            select l.category_id from operation o
            join operation_line l on l.operation_id = o.id
            where lower(o.payee) = lower(:payee) and o.type = :type and l.category_id is not null
            order by o.occurred_on desc, o.created_at desc, l.position
            limit 1
            """)
        .param("payee", payee.strip())
        .param("type", type.name())
        .query(UUID.class)
        .optional();
  }

  /** Категории верхнего уровня по частоте использования за 90 дней — для плиток ввода. */
  public List<UUID> frequentTopLevel(CategoryKind kind) {
    Map<UUID, CategoryRef> all = categories.all();
    Map<UUID, Long> counts = new HashMap<>();
    jdbc.sql(
            """
            select l.category_id, count(*) as n from operation_line l
            join operation o on o.id = l.operation_id
            where l.category_id is not null and o.occurred_on >= current_date - 90
            group by l.category_id
            """)
        .query(
            (rs, i) -> {
              CategoryRef c = all.get(rs.getObject("category_id", UUID.class));
              if (c != null && c.kind() == kind) {
                counts.merge(c.topLevelId(), rs.getLong("n"), Long::sum);
              }
              return null;
            })
        .list();
    return counts.entrySet().stream()
        .sorted(Map.Entry.<UUID, Long>comparingByValue().reversed())
        .map(Map.Entry::getKey)
        .toList();
  }

  /** Счёт последней введённой операции — подставляется по умолчанию. */
  public Optional<UUID> lastUsedAccount() {
    return jdbc.sql(
            """
            select l.account_id from operation o
            join operation_line l on l.operation_id = o.id
            where o.source = 'MANUAL'
            order by o.created_at desc, l.position
            limit 1
            """)
        .query(UUID.class)
        .optional();
  }

  /** Остаток счёта после каждой его операции, по порядку ввода в пределах дня. */
  private Map<UUID, BigDecimal> balancesAfter(UUID accountId) {
    BigDecimal opening = accounts.get(accountId).openingBalance();
    var result = new HashMap<UUID, BigDecimal>();
    jdbc.sql(
            """
            select l.operation_id,
                   sum(l.amount) over (order by o.occurred_on, o.created_at, o.id, l.position) as running
            from operation_line l
            join operation o on o.id = l.operation_id
            where l.account_id = :account and o.status = 'CLEARED'
            order by o.occurred_on, o.created_at, o.id, l.position
            """)
        .param("account", accountId)
        .query(
            (rs, i) ->
                result.put(
                    rs.getObject("operation_id", UUID.class),
                    opening.add(rs.getBigDecimal("running"))))
        .list();
    return result;
  }

  private void applyFilter(Feed.Filter filter, StringBuilder where, Map<String, Object> params) {
    if (filter.from() != null) {
      where.append(" and o.occurred_on >= :from");
      params.put("from", filter.from());
    }
    if (filter.to() != null) {
      where.append(" and o.occurred_on <= :to");
      params.put("to", filter.to());
    }
    if (filter.type() != null) {
      where.append(" and o.type = :type");
      params.put("type", filter.type().name());
    }
    if (filter.query() != null && !filter.query().isBlank()) {
      where.append(" and (o.payee ilike :q escape '\\' or o.note ilike :q escape '\\')");
      params.put("q", "%" + escapeLike(filter.query().strip()) + "%");
    }
    if (filter.accountId() != null) {
      where.append(
          " and exists (select 1 from operation_line x"
              + " where x.operation_id = o.id and x.account_id = :account)");
      params.put("account", filter.accountId());
    }
    if (filter.categoryId() != null) {
      // категория вместе с подкатегориями
      List<UUID> ids =
          categories.all().values().stream()
              .filter(
                  c ->
                      c.id().equals(filter.categoryId())
                          || filter.categoryId().equals(c.parentId()))
              .map(CategoryRef::id)
              .toList();
      where.append(
          " and exists (select 1 from operation_line x"
              + " where x.operation_id = o.id and x.category_id in (:categories))");
      params.put("categories", ids.isEmpty() ? List.of(filter.categoryId()) : ids);
    }
  }

  private Feed assemble(
      List<Row> rows, @Nullable UUID statementAccount, Map<UUID, BigDecimal> balances) {
    Map<UUID, AccountRef> accountsById =
        accounts.all().stream().collect(Collectors.toMap(AccountRef::id, a -> a));
    Map<UUID, CategoryRef> categoriesById = categories.all();

    Map<UUID, List<Row>> byOperation = new LinkedHashMap<>();
    rows.forEach(r -> byOperation.computeIfAbsent(r.operationId(), k -> new ArrayList<>()).add(r));

    Map<LocalDate, List<Feed.Item>> itemsByDay = new LinkedHashMap<>();
    Map<LocalDate, Map<String, BigDecimal>> expenses = new HashMap<>();
    Map<LocalDate, Map<String, BigDecimal>> income = new HashMap<>();

    for (List<Row> lines : byOperation.values()) {
      Row head = lines.getFirst();
      List<Row> shown =
          statementAccount == null
              ? lines
              : lines.stream().filter(l -> l.accountId().equals(statementAccount)).toList();
      Feed.Item item =
          item(head, lines, shown, accountsById, categoriesById, balances.get(head.operationId()));
      itemsByDay.computeIfAbsent(head.date(), d -> new ArrayList<>()).add(item);

      if (head.type() == OperationType.EXPENSE || head.type() == OperationType.INCOME) {
        var totals =
            (head.type() == OperationType.EXPENSE ? expenses : income)
                .computeIfAbsent(head.date(), d -> new TreeMap<>());
        for (Row line : shown) {
          totals.merge(currencyOf(line, accountsById), line.amount(), BigDecimal::add);
        }
      }
    }

    List<Feed.Day> days =
        itemsByDay.entrySet().stream()
            .map(
                e ->
                    new Feed.Day(
                        e.getKey(),
                        e.getValue(),
                        expenses.getOrDefault(e.getKey(), Map.of()),
                        income.getOrDefault(e.getKey(), Map.of())))
            .toList();
    return new Feed(days, null);
  }

  private static Feed.Item item(
      Row head,
      List<Row> lines,
      List<Row> shown,
      Map<UUID, AccountRef> accountsById,
      Map<UUID, CategoryRef> categoriesById,
      @Nullable BigDecimal balanceAfter) {
    Map<String, BigDecimal> amounts = new LinkedHashMap<>();
    for (Row line : shown) {
      amounts.merge(currencyOf(line, accountsById), line.amount(), BigDecimal::add);
    }
    List<Feed.Money> money =
        amounts.entrySet().stream().map(e -> new Feed.Money(e.getValue(), e.getKey())).toList();

    String accountName = name(accountsById, head.accountId());
    String payeeAndNote = join(head.payee(), head.note());
    return switch (head.type()) {
      case EXPENSE, INCOME -> {
        CategoryRef first =
            head.categoryId() == null ? null : categoriesById.get(head.categoryId());
        boolean split = lines.size() > 1;
        String title =
            split
                ? "Несколько категорий · " + lines.size()
                : first == null ? "—" : first.displayName();
        String original =
            !split && head.originalAmount() != null
                ? Amounts.format(head.originalAmount()) + " " + head.originalCurrency()
                : null;
        yield new Feed.Item(
            head.operationId(),
            head.type(),
            head.date(),
            first == null ? "🏷️" : first.icon(),
            first == null ? "#6c757d" : first.color(),
            title,
            join(payeeAndNote, accountName),
            money,
            original,
            balanceAfter);
      }
      case TRANSFER -> {
        Row from = lines.stream().filter(l -> l.amount().signum() < 0).findFirst().orElse(head);
        Row to = lines.stream().filter(l -> l.amount().signum() > 0).findFirst().orElse(head);
        yield new Feed.Item(
            head.operationId(),
            head.type(),
            head.date(),
            "🔁",
            "#5c7cfa",
            name(accountsById, from.accountId()) + " → " + name(accountsById, to.accountId()),
            head.note(),
            money,
            null,
            balanceAfter);
      }
      case ADJUSTMENT ->
          new Feed.Item(
              head.operationId(),
              head.type(),
              head.date(),
              "⚖️",
              "#868e96",
              "Корректировка остатка",
              join(head.note(), accountName),
              money,
              null,
              balanceAfter);
    };
  }

  private static String currencyOf(Row line, Map<UUID, AccountRef> accountsById) {
    AccountRef account = accountsById.get(line.accountId());
    return account == null ? "?" : account.currency();
  }

  private static String name(Map<UUID, AccountRef> accountsById, UUID id) {
    AccountRef account = accountsById.get(id);
    return account == null ? "?" : account.name();
  }

  private static @Nullable String join(@Nullable String a, @Nullable String b) {
    if (a == null || a.isBlank()) {
      return b;
    }
    return b == null || b.isBlank() ? a : a + " · " + b;
  }

  static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private static Row row(ResultSet rs, int n) throws SQLException {
    return new Row(
        rs.getObject("id", UUID.class),
        OperationType.valueOf(rs.getString("type")),
        rs.getObject("occurred_on", LocalDate.class),
        rs.getString("payee"),
        rs.getString("note"),
        rs.getObject("account_id", UUID.class),
        rs.getObject("category_id", UUID.class),
        rs.getBigDecimal("amount"),
        rs.getBigDecimal("original_amount"),
        rs.getString("original_currency"));
  }

  private record Row(
      UUID operationId,
      OperationType type,
      LocalDate date,
      @Nullable String payee,
      @Nullable String note,
      UUID accountId,
      @Nullable UUID categoryId,
      BigDecimal amount,
      @Nullable BigDecimal originalAmount,
      @Nullable String originalCurrency) {}
}
