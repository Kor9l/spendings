package com.spendings.shared;

import static org.assertj.core.api.Assertions.assertThat;

import com.spendings.shared.Htmx.ToastLevel;
import org.junit.jupiter.api.Test;

class HtmxTests {

  @Test
  void toastEventContainsOnlyAsciiSoItFitsIntoAHeader() {
    String event = Htmx.toastEvent(ToastLevel.SUCCESS, "Сохранено");

    assertThat(event)
        .isEqualTo(
            "{\"toast\":{\"level\":\"success\",\"message\":"
                + "\"\\u0421\\u043e\\u0445\\u0440\\u0430\\u043d\\u0435\\u043d\\u043e\"}}");
    assertThat(event.chars()).allMatch(c -> c >= 0x20 && c <= 0x7e);
  }

  @Test
  void escapesQuotesBackslashesAndControlCharacters() {
    assertThat(Htmx.escapeJsonAscii("a\"b\\c\nd\u0001")).isEqualTo("a\\\"b\\\\c\\nd\\u0001");
  }
}
