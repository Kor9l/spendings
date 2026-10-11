package com.spendings.security;

import jakarta.validation.Valid;
import java.security.Principal;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
class AuthController {

  private final OwnerAccount owner;

  AuthController(OwnerAccount owner) {
    this.owner = owner;
  }

  @GetMapping("/login")
  String login() {
    return "security/login";
  }

  @GetMapping(SetupRequiredFilter.SETUP_PATH)
  String setupForm(Model model) {
    model.addAttribute("form", new SetupForm());
    return "security/setup";
  }

  @PostMapping(SetupRequiredFilter.SETUP_PATH)
  String setup(@Valid @ModelAttribute("form") SetupForm form, BindingResult errors) {
    if (!form.getPassword().equals(form.getPasswordConfirmation())) {
      errors.rejectValue("passwordConfirmation", "setup.passwordMismatch");
    }
    if (errors.hasErrors()) {
      return "security/setup";
    }
    owner.createOwner(form.getUsername().trim(), form.getPassword());
    return "redirect:/login?created";
  }

  /** Имя пользователя для шапки на всех страницах. */
  @ControllerAdvice
  static class CurrentUserAdvice {

    @ModelAttribute("currentUsername")
    @Nullable String currentUsername(@Nullable Principal principal) {
      return principal == null ? null : principal.getName();
    }
  }
}
