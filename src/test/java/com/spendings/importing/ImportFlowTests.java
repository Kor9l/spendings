package com.spendings.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import com.spendings.Fixtures;
import com.spendings.IntegrationTest;
import com.spendings.account.Accounts;
import com.spendings.importing.Importer.Status;
import com.spendings.operation.OperationStats;
import com.spendings.operation.Operations;
import com.spendings.operation.RecordOperation.RecordExpense;
import com.spendings.operation.RecordOperation.Split;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@IntegrationTest
class ImportFlowTests {

  @Autowired Importer importer;
  @Autowired Operations operations;
  @Autowired OperationStats stats;
  @Autowired Accounts accounts;
  @Autowired CategorizationRules rules;
  @Autowired Fixtures fixtures;
  @Autowired JdbcClient jdbc;
  @Autowired com.spendings.backup.Backup backup;
  @Autowired MockMvcTester mvc;

  UUID card;
  TableFile table;
  ImportMapping mapping;

  @BeforeEach
  void setUp() {
    card = fixtures.account("Карта", "BYN", "1000");
    table =
        TableFile.read(
            "vypiska.csv", TableFileTests.BANK_CSV.getBytes(TableFile.WINDOWS_1251), null, null);
    mapping = ImportMapping.guess(table);
    mapping.setAccountId(card);
  }

  @Test
  void importedOperationsWaitInTheInboxUntilConfirmed() {
    Importer.Result result = importer.commit(table, mapping, "vypiska.csv", null);

    assertThat(result.imported()).isEqualTo(4);
    assertThat(stats.inboxCount()).isEqualTo(4);
    assertThat(accounts.balanceOf(card))
        .as("до подтверждения остаток не меняется")
        .isEqualByComparingTo("1000");

    assertThat(operations.confirmBatch(result.batchId())).isEqualTo(4);
    assertThat(stats.inboxCount()).isZero();
    assertThat(accounts.balanceOf(card))
        .isEqualByComparingTo("2266.38"); // 1000 − 62,10 − 6,50 + 1400 − 65,02
  }

  @Test
  void sameFileTwiceAddsNothingButIdenticalRowsInOneFileStay() {
    importer.commit(table, mapping, "vypiska.csv", null);

    Importer.Preview again = importer.preview(table, mapping);
    assertThat(again.count(Status.ALREADY_IMPORTED)).isEqualTo(4);
    assertThat(importer.commit(table, mapping, "vypiska.csv", null).imported()).isZero();

    String twoCoffees = "Дата;Описание;Сумма\n03.10.2026;Кофе;-5\n03.10.2026;Кофе;-5\n";
    TableFile coffees = TableFile.read("c.csv", twoCoffees.getBytes(), null, null);
    ImportMapping m = ImportMapping.guess(coffees);
    m.setAccountId(card);
    assertThat(importer.commit(coffees, m, "c.csv", null).imported()).isEqualTo(2);
  }

  @Test
  void rowMatchingAManualOperationIsSkippedAsProbableDuplicate() {
    UUID cafe = fixtures.category("Еда", "Кафе и рестораны");
    operations.record(
        new RecordExpense(
            card,
            LocalDate.of(2026, 10, 4),
            List.of(new Split(cafe, new BigDecimal("6.50"))),
            "Зерно",
            null));

    Importer.Preview preview = importer.preview(table, mapping);
    assertThat(preview.rows())
        .filteredOn(r -> r.status() == Status.PROBABLE_DUPLICATE)
        .singleElement()
        .satisfies(r -> assertThat(r.row().amount()).isEqualByComparingTo("-6.50"));
    assertThat(importer.commit(table, mapping, "vypiska.csv", null).imported()).isEqualTo(3);
  }

  @Test
  void rulesCategorizeAndUndoRemovesTheWholeBatch() {
    UUID groceries = fixtures.category("Еда", "Продукты");
    rules.learn("EUROOPT", groceries, com.spendings.category.CategoryKind.EXPENSE);

    Importer.Result result = importer.commit(table, mapping, "vypiska.csv", null);
    assertThat(fixtures.linesWithCategory(groceries)).isEqualTo(1);
    operations.confirmBatch(result.batchId());

    assertThat(importer.undo(result.batchId())).isEqualTo(4);
    assertThat(accounts.balanceOf(card)).isEqualByComparingTo("1000");
    assertThat(importer.preview(table, mapping).count(Status.NEW)).isEqualTo(4);
  }

  @Test
  void wholeFlowThroughThePages() throws Exception {
    var file =
        new MockMultipartFile(
            "file",
            "vypiska.csv",
            "text/csv",
            TableFileTests.BANK_CSV.getBytes(TableFile.WINDOWS_1251));
    var upload =
        mvc.post()
            .multipart()
            .uri("/import/upload")
            .file(file)
            .with(user("owner"))
            .with(csrf())
            .exchange();
    assertThat(upload).hasStatus3xxRedirection();
    String location = upload.getResponse().getRedirectedUrl();

    assertThat(mvc.get().uri(location).with(user("owner")))
        .hasStatusOk()
        .bodyText()
        .contains("Оплата: EUROOPT, MINSK")
        .contains("новая");

    assertThat(
            mvc.post()
                .uri(location + "/commit")
                .with(user("owner"))
                .with(csrf())
                .param("accountId", card.toString())
                .param("encoding", "windows-1251")
                .param("delimiter", ";")
                .param("headerRow", "2")
                .param("dateColumn", "0")
                .param("dateFormat", "dd.MM.yyyy HH:mm:ss")
                .param("descriptionColumn", "1")
                .param("amountColumn", "2")
                .param("saveAsProfile", "Банк — карта"))
        .hasStatus3xxRedirection()
        .hasRedirectedUrl("/inbox");

    assertThat(mvc.get().uri("/inbox").with(user("owner")))
        .hasStatusOk()
        .bodyText()
        .contains("Оплата: AMAZON");
    assertThat(mvc.get().uri("/import").with(user("owner")))
        .hasStatusOk()
        .bodyText()
        .contains("Банк — карта");

    UUID amazon =
        jdbc.sql("select id from operation where payee = 'Оплата: AMAZON'")
            .query(UUID.class)
            .single();
    UUID books = fixtures.category("Образование");
    assertThat(
            mvc.post()
                .uri("/inbox/" + amazon + "/category")
                .with(user("owner"))
                .with(csrf())
                .param("categoryId", books.toString())
                .param("remember", "true"))
        .hasStatusOk()
        .hasHeader(
            "HX-Trigger",
            com.spendings.shared.Htmx.toastEvent(
                com.spendings.shared.Htmx.ToastLevel.SUCCESS, "Правило сохранено"));
    assertThat(rules.all()).anySatisfy(r -> assertThat(r.pattern()).isEqualTo("Оплата: AMAZON"));
    assertThat(mvc.get().uri("/import/rules").with(user("owner")))
        .hasStatusOk()
        .bodyText()
        .contains("Образование");
    assertThat(mvc.get().uri("/").with(user("owner"))).hasStatusOk();
  }

  @Test
  void backupRestoresIntoAnEmptyInstall() throws Exception {
    Importer.Result result = importer.commit(table, mapping, "vypiska.csv", null);
    operations.confirmBatch(result.batchId());
    rules.learn(
        "AMAZON", fixtures.category("Образование"), com.spendings.category.CategoryKind.EXPENSE);
    BigDecimal balance = accounts.balanceOf(card);

    var out = new ByteArrayOutputStream();
    backup.export(out);
    assertThat(out.toString())
        .contains("\"format\":\"spendings-backup\"")
        .contains("Оплата: AMAZON");

    // «пустая установка»: удаляем данные так же, как их нет на свежем сервере
    jdbc.sql("delete from operation").update();
    jdbc.sql("delete from import_batch").update();
    jdbc.sql("delete from account").update();
    assertThat(backup.restore(new ByteArrayInputStream(out.toByteArray())).rows())
        .isGreaterThan(10);

    UUID restored =
        jdbc.sql("select id from account where name = 'Карта'").query(UUID.class).single();
    assertThat(restored).isEqualTo(card);
    assertThat(accounts.balanceOf(card)).isEqualByComparingTo(balance);
    assertThat(rules.all()).hasSize(1);

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> backup.restore(new ByteArrayInputStream(out.toByteArray()))))
        .isInstanceOfSatisfying(
            com.spendings.shared.DomainException.class,
            e -> assertThat(e.messageKey()).isEqualTo("backup.error.notEmpty"));
  }
}
