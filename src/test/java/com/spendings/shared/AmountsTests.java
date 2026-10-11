package com.spendings.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class AmountsTests {

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "85,40        | 85.40",
        "85.40        | 85.40",
        "1 234,56     | 1234.56",
        "1 234,56 | 1234.56",
        "1 234,56 | 1234.56",
        "1.234,56     | 1234.56",
        "1,234.56     | 1234.56",
        "3200         | 3200",
        "-12,30       | -12.30",
        "−12,30  | -12.30",
        "+5           | 5",
        ",5           | 0.5"
      })
  void parsesAmountsTheWayPeopleTypeThem(String text, BigDecimal expected) {
    assertThat(Amounts.parse(text)).isEqualByComparingTo(expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "  ", "abc", "1,2,3x", "12-3", "--5"})
  void rejectsWhatIsNotANumber(String text) {
    assertThatIllegalArgumentException().isThrownBy(() -> Amounts.parse(text));
  }

  @org.junit.jupiter.api.Test
  void formatsWithNonBreakingSpacesAndRealMinus() {
    assertThat(Amounts.format(new BigDecimal("-1234.5"))).isEqualTo("−1 234,50");
    assertThat(Amounts.formatSigned(new BigDecimal("3200"))).isEqualTo("+3 200,00");
    assertThat(Amounts.forInput(new BigDecimal("85.4000"))).isEqualTo("85,4");
    assertThat(Amounts.forInput(new BigDecimal("1E+3"))).isEqualTo("1000");
  }
}
