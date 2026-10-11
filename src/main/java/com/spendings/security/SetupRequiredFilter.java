package com.spendings.security;

import com.spendings.shared.Htmx;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.web.filter.OncePerRequestFilter;

/** Пока владелец не создан, всё ведёт на мастер первого запуска. После создания мастер закрыт. */
final class SetupRequiredFilter extends OncePerRequestFilter {

  static final String SETUP_PATH = "/setup";

  private static final List<String> ALWAYS_OPEN =
      List.of(
          "/css/",
          "/js/",
          "/webjars/",
          "/icons/",
          "/favicon.svg",
          "/manifest.webmanifest",
          "/sw.js",
          "/offline.html",
          "/actuator/health",
          "/error");

  private final OwnerAccount owner;

  SetupRequiredFilter(OwnerAccount owner) {
    this.owner = owner;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    boolean setUp = owner.isSetUp();

    if (!setUp && !path.equals(SETUP_PATH) && ALWAYS_OPEN.stream().noneMatch(path::startsWith)) {
      redirect(request, response, SETUP_PATH);
      return;
    }
    if (setUp && path.equals(SETUP_PATH)) {
      redirect(request, response, "/login");
      return;
    }
    chain.doFilter(request, response);
  }

  private static void redirect(HttpServletRequest request, HttpServletResponse response, String to)
      throws IOException {
    String target = request.getContextPath() + to;
    if (Htmx.isHtmxRequest(request)) {
      response.setHeader(Htmx.REDIRECT, target);
      response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    } else {
      response.sendRedirect(target);
    }
  }
}
