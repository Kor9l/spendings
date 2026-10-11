package com.spendings.shared;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Заголовки htmx: определить htmx-запрос, перенаправить, показать уведомление. */
public final class Htmx {

  public static final String REQUEST = "HX-Request";
  public static final String REDIRECT = "HX-Redirect";
  public static final String TRIGGER = "HX-Trigger";

  private Htmx() {}

  public static boolean isHtmxRequest(HttpServletRequest request) {
    return "true".equals(request.getHeader(REQUEST));
  }

  /** Всплывающее уведомление: событие {@code toast} обрабатывает {@code static/js/app.js}. */
  public static void toast(HttpServletResponse response, ToastLevel level, String message) {
    response.setHeader(TRIGGER, toastEvent(level, message));
  }

  public static String toastEvent(ToastLevel level, String message) {
    return "{\"toast\":{\"level\":\""
        + level.cssClass()
        + "\",\"message\":\""
        + escapeJsonAscii(message)
        + "\"}}";
  }

  /**
   * Экранирует строку для JSON так, чтобы в ней остались только ASCII-символы: заголовки HTTP не
   * переносят кириллицу, а htmx разбирает {@code \\uXXXX} при чтении JSON.
   */
  static String escapeJsonAscii(String value) {
    var out = new StringBuilder(value.length() + 16);
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20 || c > 0x7e) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.toString();
  }

  public enum ToastLevel {
    SUCCESS("success"),
    WARNING("warning"),
    ERROR("danger");

    private final String cssClass;

    ToastLevel(String cssClass) {
      this.cssClass = cssClass;
    }

    String cssClass() {
      return cssClass;
    }
  }
}
