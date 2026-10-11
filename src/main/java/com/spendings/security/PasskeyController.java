package com.spendings.security;

import java.security.Principal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
class PasskeyController {

  private final Passkeys passkeys;

  PasskeyController(Passkeys passkeys) {
    this.passkeys = passkeys;
  }

  @GetMapping("/settings/passkeys")
  String list(Principal principal, Model model) {
    model.addAttribute("passkeys", passkeys.of(principal.getName()));
    return "security/passkeys";
  }

  @PostMapping("/settings/passkeys/delete")
  String delete(Principal principal, @RequestParam String id, RedirectAttributes redirect) {
    if (passkeys.delete(principal.getName(), id)) {
      redirect.addFlashAttribute("toast", "Ключ удалён");
    }
    return "redirect:/settings/passkeys";
  }
}
