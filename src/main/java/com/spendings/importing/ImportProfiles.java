package com.spendings.importing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Сохранённые настройки чтения выписок разных банков. */
@Component
@Transactional(readOnly = true)
class ImportProfiles {

  private final JdbcClient jdbc;

  ImportProfiles(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  List<Profile> all() {
    return jdbc.sql("select id, name, account_id from import_profile order by name")
        .query(
            (rs, i) ->
                new Profile(
                    rs.getObject("id", UUID.class),
                    rs.getString("name"),
                    rs.getObject("account_id", UUID.class)))
        .list();
  }

  /** Настройки профиля в виде сопоставления колонок. */
  Optional<ImportMapping> mapping(UUID id) {
    return jdbc.sql("select * from import_profile where id = ?")
        .param(id)
        .query(
            (rs, i) -> {
              var m = new ImportMapping();
              m.setProfileId(id);
              m.setAccountId(rs.getObject("account_id", UUID.class));
              m.setEncoding(rs.getString("encoding"));
              m.setDelimiter(rs.getString("delimiter"));
              m.setHeaderRow(rs.getInt("header_row"));
              m.setDateColumn(rs.getObject("date_column", Integer.class));
              m.setDateFormat(rs.getString("date_format"));
              m.setAmountColumn(rs.getObject("amount_column", Integer.class));
              m.setDebitColumn(rs.getObject("debit_column", Integer.class));
              m.setCreditColumn(rs.getObject("credit_column", Integer.class));
              m.setInvertSign(rs.getBoolean("invert_sign"));
              m.setDescriptionColumn(rs.getObject("description_column", Integer.class));
              m.setOriginalAmountColumn(rs.getObject("original_amount_column", Integer.class));
              m.setOriginalCurrencyColumn(rs.getObject("original_currency_column", Integer.class));
              return m;
            })
        .optional();
  }

  /** Сохраняет профиль под именем; профиль с тем же именем перезаписывается. */
  @Transactional
  UUID save(String name, ImportMapping m) {
    return jdbc.sql(
            """
            insert into import_profile (name, account_id, encoding, delimiter, header_row, date_column, date_format,
                amount_column, debit_column, credit_column, invert_sign, description_column,
                original_amount_column, original_currency_column)
            values (:name, :account, :encoding, :delimiter, :headerRow, :dateColumn, :dateFormat,
                :amount, :debit, :credit, :invert, :description, :originalAmount, :originalCurrency)
            on conflict (name) do update set account_id = excluded.account_id, encoding = excluded.encoding,
                delimiter = excluded.delimiter, header_row = excluded.header_row, date_column = excluded.date_column,
                date_format = excluded.date_format, amount_column = excluded.amount_column,
                debit_column = excluded.debit_column, credit_column = excluded.credit_column,
                invert_sign = excluded.invert_sign, description_column = excluded.description_column,
                original_amount_column = excluded.original_amount_column,
                original_currency_column = excluded.original_currency_column
            returning id
            """)
        .param("name", name.strip())
        .param("account", m.getAccountId())
        .param("encoding", m.getEncoding())
        .param("delimiter", String.valueOf(m.delimiterChar()))
        .param("headerRow", m.getHeaderRow())
        .param("dateColumn", m.getDateColumn())
        .param("dateFormat", m.getDateFormat())
        .param("amount", m.getAmountColumn())
        .param("debit", m.getDebitColumn())
        .param("credit", m.getCreditColumn())
        .param("invert", m.isInvertSign())
        .param("description", m.getDescriptionColumn())
        .param("originalAmount", m.getOriginalAmountColumn())
        .param("originalCurrency", m.getOriginalCurrencyColumn())
        .query(UUID.class)
        .single();
  }

  @Transactional
  void delete(UUID id) {
    jdbc.sql("delete from import_profile where id = ?").param(id).update();
  }

  record Profile(UUID id, String name, @Nullable UUID accountId) {}
}
