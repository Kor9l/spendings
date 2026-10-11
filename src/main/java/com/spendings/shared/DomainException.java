package com.spendings.shared;

/**
 * Нарушено правило предметной области. {@code messageKey} — ключ из {@code messages.properties},
 * контроллер показывает его пользователю рядом с формой.
 */
public class DomainException extends RuntimeException {

  private final String messageKey;
  private final Object[] arguments;

  public DomainException(String messageKey, Object... arguments) {
    super(messageKey);
    this.messageKey = messageKey;
    this.arguments = arguments;
  }

  public String messageKey() {
    return messageKey;
  }

  public Object[] arguments() {
    return arguments;
  }
}
