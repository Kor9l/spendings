package com.spendings.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** Разбор и вывод денежных сумм. Деньги — только {@link BigDecimal}. */
public final class Amounts {

  /** Минус для вывода: U+2212, а не дефис. */
  public static final char MINUS = '−';

  private Amounts() {}

  /**
   * Разбирает сумму в том виде, как её вводят люди: {@code 1 234,56}, {@code 1234.56}, {@code
   * 1.234,56}, с неразрывными пробелами из скопированного текста.
   *
   * @throws IllegalArgumentException если строка не похожа на число
   */
  public static BigDecimal parse(String text) {
    String s = text.strip().replaceAll("[\\s\\u00A0\\u2007\\u202F']", "");
    if (s.isEmpty()) {
      throw new IllegalArgumentException("Пустая сумма");
    }
    boolean negative = false;
    if (s.charAt(0) == '-' || s.charAt(0) == MINUS) {
      negative = true;
      s = s.substring(1);
    } else if (s.charAt(0) == '+') {
      s = s.substring(1);
    }

    int lastComma = s.lastIndexOf(',');
    int lastDot = s.lastIndexOf('.');
    int decimalSeparator = Math.max(lastComma, lastDot);
    if (lastComma >= 0 && lastDot >= 0) {
      // и запятая, и точка: последний знак — десятичный, остальные — разделители тысяч
      String integerPart = s.substring(0, decimalSeparator).replace(",", "").replace(".", "");
      s = integerPart + "." + s.substring(decimalSeparator + 1);
    } else if (lastComma >= 0) {
      s = s.replace(',', '.');
    }

    if (!s.matches("\\d+(\\.\\d*)?|\\.\\d+")) {
      throw new IllegalArgumentException("Не число: " + text);
    }
    BigDecimal value = new BigDecimal(s);
    return negative ? value.negate() : value;
  }

  /** {@code 1 234,56}: группы через неразрывный пробел, два знака после запятой. */
  public static String format(@Nullable BigDecimal amount) {
    if (amount == null) {
      return "";
    }
    String formatted = formatter().format(amount.abs().setScale(2, RoundingMode.HALF_EVEN));
    return amount.signum() < 0 ? MINUS + formatted : formatted;
  }

  /** То же, но со знаком всегда: {@code +3 200,00}, {@code −85,40}. */
  public static String formatSigned(BigDecimal amount) {
    return amount.signum() > 0 ? "+" + format(amount) : format(amount);
  }

  /** Для поля ввода: {@code 1234,56}, без разделителей тысяч и лишних нулей после запятой. */
  public static String forInput(@Nullable BigDecimal amount) {
    if (amount == null) {
      return "";
    }
    BigDecimal value = amount.stripTrailingZeros();
    if (value.scale() < 0) {
      value = value.setScale(0);
    }
    return value.toPlainString().replace('.', ',');
  }

  private static DecimalFormat formatter() {
    var symbols = DecimalFormatSymbols.getInstance(Locale.ROOT);
    symbols.setGroupingSeparator(' ');
    symbols.setDecimalSeparator(',');
    var format = new DecimalFormat("#,##0.00", symbols);
    format.setRoundingMode(RoundingMode.HALF_EVEN);
    return format;
  }
}
