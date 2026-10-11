package com.spendings.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import com.spendings.TestcontainersConfiguration;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Путь нового пользователя по шагам: мастер первого запуска → вход → дашборд. Шаги зависят друг от
 * друга (общая база), поэтому порядок задан явно.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FirstRunAndLoginTests {

  private static final String PASSWORD = "correct-horse-battery";

  @Autowired MockMvcTester mvc;
  @Autowired OwnerAccount owner;

  @Test
  @Order(1)
  void beforeSetupEveryPageLeadsToTheWizard() {
    assertThat(mvc.get().uri("/")).hasStatus3xxRedirection().hasRedirectedUrl("/setup");
    assertThat(mvc.get().uri("/login")).hasStatus3xxRedirection().hasRedirectedUrl("/setup");
    assertThat(mvc.get().uri("/setup")).hasStatusOk().bodyText().contains("Первый запуск");
    // PWA ставится и с экрана первого запуска
    assertThat(mvc.get().uri("/manifest.webmanifest")).hasStatusOk();
    assertThat(mvc.get().uri("/sw.js")).hasStatusOk();
    assertThat(mvc.get().uri("/icons/icon-192.png")).hasStatusOk();
  }

  @Test
  @Order(2)
  void wizardRejectsMismatchedPasswords() {
    assertThat(
            mvc.post()
                .uri("/setup")
                .with(csrf())
                .param("username", "owner")
                .param("password", PASSWORD)
                .param("passwordConfirmation", "something-else-entirely"))
        .hasStatusOk()
        .bodyText()
        .contains("Пароли не совпадают");
    assertThat(owner.isSetUp()).isFalse();
  }

  @Test
  @Order(3)
  void wizardCreatesTheOwnerAndThenCloses() {
    assertThat(
            mvc.post()
                .uri("/setup")
                .with(csrf())
                .param("username", "owner")
                .param("password", PASSWORD)
                .param("passwordConfirmation", PASSWORD))
        .hasStatus3xxRedirection()
        .hasRedirectedUrl("/login?created");

    assertThat(owner.isSetUp()).isTrue();
    assertThat(mvc.get().uri("/setup")).hasStatus3xxRedirection().hasRedirectedUrl("/login");
    assertThatIllegalStateException().isThrownBy(() -> owner.createOwner("intruder", PASSWORD));
  }

  @Test
  @Order(4)
  void ownerLogsInWithThePasswordFromTheWizard() {
    assertThat(mvc.perform(formLogin().user("owner").password(PASSWORD)))
        .hasStatus3xxRedirection()
        .hasRedirectedUrl("/");
    assertThat(mvc.perform(formLogin().user("owner").password("wrong-password")))
        .hasStatus3xxRedirection()
        .hasRedirectedUrl("/login?error");
  }

  @Test
  @Order(5)
  void dashboardShowsTheBaseCurrency() {
    assertThat(mvc.get().uri("/").with(user("owner")))
        .hasStatusOk()
        .bodyText()
        .contains("Начнём со счёта")
        .contains("BYN");
    assertThat(mvc.get().uri("/system/status").with(user("owner")))
        .hasStatusOk()
        .bodyText()
        .contains("PostgreSQL 18");
  }

  @Test
  @Order(6)
  void expiredSessionInHtmxRequestRedirectsTheWholePage() {
    assertThat(mvc.get().uri("/system/status").header("HX-Request", "true"))
        .hasStatus(401)
        .hasHeader("HX-Redirect", "/login");
  }
}
