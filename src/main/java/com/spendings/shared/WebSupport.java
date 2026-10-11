package com.spendings.shared;

import java.beans.PropertyEditorSupport;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Общие правила веб-слоя для всех модулей. Уведомление после перенаправления: {@code
 * redirect.addFlashAttribute("toast", "…")} — его показывает {@code layout.html}.
 */
@ControllerAdvice
class WebSupport {

  /** Суммы в формах разбираются как их вводят люди; ошибка — {@code typeMismatch}. */
  @InitBinder
  void amounts(WebDataBinder binder) {
    binder.registerCustomEditor(
        BigDecimal.class,
        new PropertyEditorSupport() {
          @Override
          public void setAsText(String text) {
            setValue(text.isBlank() ? null : Amounts.parse(text));
          }

          @Override
          public String getAsText() {
            return Amounts.forInput((BigDecimal) getValue());
          }
        });
  }

  @ExceptionHandler(NotFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  String notFound() {
    return "error/404";
  }
}
