package com.spendings.backup;

import com.spendings.shared.DomainException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDate;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
class BackupController {

  private final Backup backup;
  private final MessageSource messages;

  BackupController(Backup backup, MessageSource messages) {
    this.backup = backup;
    this.messages = messages;
  }

  @GetMapping("/settings/backup.json")
  void download(HttpServletResponse response) throws IOException {
    response.setContentType("application/json");
    response.setHeader(
        "Content-Disposition",
        "attachment; filename=\"spendings-backup-" + LocalDate.now() + ".json\"");
    backup.export(response.getOutputStream());
  }

  @PostMapping("/settings/restore")
  String restore(@RequestParam("file") MultipartFile file, RedirectAttributes redirect) {
    try {
      Backup.Summary summary = backup.restore(file.getInputStream());
      redirect.addFlashAttribute("toast", "Восстановлено записей: " + summary.rows());
    } catch (DomainException e) {
      redirect.addFlashAttribute(
          "toast", messages.getMessage(e.messageKey(), null, LocaleContextHolder.getLocale()));
      redirect.addFlashAttribute("toastLevel", "danger");
    } catch (IOException | RuntimeException e) {
      redirect.addFlashAttribute("toast", "Не удалось прочитать файл бэкапа");
      redirect.addFlashAttribute("toastLevel", "danger");
    }
    return "redirect:/settings";
  }
}
