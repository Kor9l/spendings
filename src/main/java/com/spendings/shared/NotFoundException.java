package com.spendings.shared;

/** Запрошенной записи нет — пользователь видит страницу 404. */
public class NotFoundException extends RuntimeException {

  public NotFoundException(String what) {
    super(what + " не найдено");
  }
}
