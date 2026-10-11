package com.spendings.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.spendings.IntegrationTest;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
class DemoDataTests {

  @Autowired DemoData demo;
  @Autowired JdbcClient jdbc;

  @Test
  void generatesVariedExpensesCoveredBySalaryAndOnlyIntoAnEmptyDatabase() {
    int created = demo.generate(2_000);

    assertThat(created).isBetween(1_990, 2_000);
    assertThat(
            jdbc.sql("select count(distinct category_id) from operation_line where amount < 0")
                .query(Integer.class)
                .single())
        .isGreaterThan(20);
    BigDecimal income =
        jdbc.sql("select sum(amount) from operation_line where amount > 0")
            .query(BigDecimal.class)
            .single();
    BigDecimal spent =
        jdbc.sql("select -sum(amount) from operation_line where amount < 0")
            .query(BigDecimal.class)
            .single();
    assertThat(income).isGreaterThan(spent);
    assertThat(
            jdbc.sql("select count(*) from account where opening_balance < 0")
                .query(Integer.class)
                .single())
        .isZero();

    assertThat(demo.generate(2_000)).isZero();
  }
}
