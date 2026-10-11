package com.spendings.security;

import com.spendings.shared.Htmx;
import jakarta.servlet.http.HttpServletResponse;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.RequestHeaderRequestMatcher;
import org.springframework.security.web.webauthn.management.JdbcPublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.JdbcUserCredentialRepository;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;

@Configuration
@EnableWebSecurity
class SecurityConfig {

  private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);
  private static final int REMEMBER_ME_SECONDS = 30 * 24 * 60 * 60;

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      OwnerAccount owner,
      @Value("${spendings.security.remember-me-key:}") String rememberMeKey,
      @Value("${spendings.security.webauthn.rp-id}") String rpId,
      @Value("${spendings.security.webauthn.allowed-origins}") Set<String> allowedOrigins)
      throws Exception {
    http.authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/setup",
                        "/login",
                        "/error",
                        "/actuator/health",
                        "/css/**",
                        "/js/**",
                        "/webjars/**",
                        "/favicon.svg",
                        "/icons/**",
                        "/manifest.webmanifest",
                        "/sw.js",
                        "/offline.html")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .formLogin(form -> form.loginPage("/login").defaultSuccessUrl("/", true).permitAll())
        // passkeys: свои страницы входа и управления ключами, стандартную страницу регистрации —
        // нет
        .webAuthn(
            webAuthn ->
                webAuthn
                    .rpName("Spendings")
                    .rpId(rpId)
                    .allowedOrigins(allowedOrigins)
                    .disableDefaultRegistrationPage(true))
        .rememberMe(
            remember ->
                remember
                    .key(rememberMeKeyOrRandom(rememberMeKey))
                    .userDetailsService(owner)
                    .tokenValiditySeconds(REMEMBER_ME_SECONDS))
        .logout(logout -> logout.logoutSuccessUrl("/login?logout"))
        // htmx-запрос после истечения сессии: вместо HTML страницы входа внутри фрагмента —
        // переход на /login целиком
        .exceptionHandling(
            errors ->
                errors.defaultAuthenticationEntryPointFor(
                    (request, response, ex) -> {
                      response.setHeader(Htmx.REDIRECT, request.getContextPath() + "/login");
                      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    },
                    new RequestHeaderRequestMatcher(Htmx.REQUEST, "true")))
        .addFilterBefore(
            new SetupRequiredFilter(owner), UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  @Bean
  PublicKeyCredentialUserEntityRepository webAuthnUsers(JdbcOperations jdbc) {
    return new JdbcPublicKeyCredentialUserEntityRepository(jdbc);
  }

  @Bean
  UserCredentialRepository webAuthnCredentials(JdbcOperations jdbc) {
    return new JdbcUserCredentialRepository(jdbc);
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

  /** Без заданного ключа cookie «Запомнить меня» перестают действовать после перезапуска. */
  private static String rememberMeKeyOrRandom(String configured) {
    if (!configured.isBlank()) {
      return configured;
    }
    log.warn(
        "SPENDINGS_REMEMBER_ME_KEY не задан: ключ remember-me сгенерирован случайно, "
            + "после перезапуска придётся войти заново");
    var bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }
}
