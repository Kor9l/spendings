package com.spendings.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Форма мастера первого запуска. Изменяемый класс, чтобы с ним работал {@code th:field}. */
class SetupForm {

  @NotBlank
  @Size(min = 3, max = 50)
  @Pattern(regexp = "[A-Za-z0-9._-]*", message = "{setup.usernamePattern}")
  private String username = "";

  @NotBlank
  @Size(min = 10, max = 128)
  private String password = "";

  @NotBlank private String passwordConfirmation = "";

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }

  public String getPasswordConfirmation() {
    return passwordConfirmation;
  }

  public void setPasswordConfirmation(String passwordConfirmation) {
    this.passwordConfirmation = passwordConfirmation;
  }
}
