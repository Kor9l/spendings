package com.spendings.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import com.spendings.IntegrationTest;
import java.util.Arrays;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Скорость на объёме: 50 тысяч операций за три года, дашборд и лента быстрее 300 мс (медиана). Не
 * входит в обычный {@code test}: {@code ./gradlew perfTest}.
 */
@Tag("perf")
@IntegrationTest
class DashboardPerformanceTests {

  static final int OPERATIONS = 50_000;
  static final long LIMIT_MS = 300;

  @Autowired DemoData demo;
  @Autowired MockMvcTester mvc;

  @Test
  void dashboardAndPagesStayFastOnFiftyThousandOperations() {
    assertThat(demo.generate(OPERATIONS)).isGreaterThan(OPERATIONS - 10);

    for (String page : new String[] {"/", "/operations", "/reports", "/budgets", "/deposits"}) {
      long median = medianMillis(page);
      System.out.printf("%-12s медиана %d мс%n", page, median);
      assertThat(median).as(page).isLessThan(LIMIT_MS);
    }
  }

  private long medianMillis(String uri) {
    for (int i = 0; i < 3; i++) {
      assertThat(mvc.get().uri(uri).with(user("owner"))).hasStatusOk();
    }
    long[] times = new long[9];
    for (int i = 0; i < times.length; i++) {
      long started = System.nanoTime();
      assertThat(mvc.get().uri(uri).with(user("owner"))).hasStatusOk();
      times[i] = (System.nanoTime() - started) / 1_000_000;
    }
    Arrays.sort(times);
    return times[times.length / 2];
  }
}
