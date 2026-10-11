package com.spendings.importing;

import com.spendings.account.Accounts;
import com.spendings.category.Categories;
import com.spendings.shared.DomainException;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Мастер импорта: файл → колонки → предпросмотр → «Входящие». */
@Controller
@RequestMapping("/import")
class ImportController {

  static final int RAW_ROWS = 12;
  static final int PREVIEW_ROWS = 60;

  private final Uploads uploads;
  private final Importer importer;
  private final ImportProfiles profiles;
  private final Accounts accounts;
  private final Categories categories;

  ImportController(
      Uploads uploads,
      Importer importer,
      ImportProfiles profiles,
      Accounts accounts,
      Categories categories) {
    this.uploads = uploads;
    this.importer = importer;
    this.profiles = profiles;
    this.accounts = accounts;
    this.categories = categories;
  }

  @GetMapping
  String start(Model model) {
    model.addAttribute("accounts", accounts.active());
    model.addAttribute("profiles", profiles.all());
    model.addAttribute("batches", importer.batches());
    return "importing/start";
  }

  @PostMapping("/upload")
  String upload(
      @RequestParam("file") MultipartFile file,
      @RequestParam(required = false) @Nullable UUID profileId,
      RedirectAttributes redirect)
      throws IOException {
    if (file.isEmpty()) {
      redirect.addFlashAttribute("toast", "Выберите файл выписки");
      redirect.addFlashAttribute("toastLevel", "danger");
      return "redirect:/import";
    }
    String name = file.getOriginalFilename() == null ? "выписка" : file.getOriginalFilename();
    UUID id = uploads.put(name, file.getBytes());
    return "redirect:/import/" + id + (profileId == null ? "" : "?profileId=" + profileId);
  }

  @GetMapping("/{uploadId}")
  String mapping(
      @PathVariable UUID uploadId,
      @RequestParam(required = false) @Nullable UUID profileId,
      Model model,
      RedirectAttributes redirect) {
    var upload = uploads.get(uploadId);
    if (upload.isEmpty()) {
      return expired(redirect);
    }
    ImportMapping mapping;
    TableFile table;
    var saved =
        profileId == null ? java.util.Optional.<ImportMapping>empty() : profiles.mapping(profileId);
    if (saved.isPresent()) {
      mapping = saved.get();
      table = read(upload.get(), mapping);
    } else {
      table = TableFile.read(upload.get().fileName(), upload.get().content(), null, null);
      mapping = ImportMapping.guess(table);
      mapping.setAccountId(accounts.active().isEmpty() ? null : accounts.active().getFirst().id());
    }
    return workspace(model, upload.get(), table, mapping, false);
  }

  /** Пересчёт предпросмотра после правки колонок — фрагмент для htmx. */
  @PostMapping("/{uploadId}/preview")
  String preview(
      @PathVariable UUID uploadId,
      @ModelAttribute("mapping") ImportMapping mapping,
      Model model,
      RedirectAttributes redirect) {
    var upload = uploads.get(uploadId);
    if (upload.isEmpty()) {
      return expired(redirect);
    }
    return workspace(model, upload.get(), read(upload.get(), mapping), mapping, true);
  }

  @PostMapping("/{uploadId}/commit")
  String commit(
      @PathVariable UUID uploadId,
      @ModelAttribute("mapping") ImportMapping mapping,
      Model model,
      RedirectAttributes redirect) {
    var upload = uploads.get(uploadId);
    if (upload.isEmpty()) {
      return expired(redirect);
    }
    TableFile table = read(upload.get(), mapping);
    try {
      UUID profileId = mapping.getProfileId();
      if (mapping.getSaveAsProfile() != null && !mapping.getSaveAsProfile().isBlank()) {
        profileId = profiles.save(mapping.getSaveAsProfile(), mapping);
      }
      Importer.Result result = importer.commit(table, mapping, upload.get().fileName(), profileId);
      uploads.remove(uploadId);
      redirect.addFlashAttribute(
          "toast",
          "Во «Входящие» добавлено: " + result.imported() + ", пропущено: " + result.skipped());
      return "redirect:/inbox";
    } catch (DomainException e) {
      model.addAttribute("error", e.messageKey());
      return workspace(model, upload.get(), table, mapping, false);
    }
  }

  @PostMapping("/batches/{batchId}/undo")
  String undo(@PathVariable UUID batchId, RedirectAttributes redirect) {
    int deleted = importer.undo(batchId);
    redirect.addFlashAttribute("toast", "Импорт отменён, удалено операций: " + deleted);
    return "redirect:/import";
  }

  @PostMapping("/profiles/{id}/delete")
  String deleteProfile(@PathVariable UUID id, RedirectAttributes redirect) {
    profiles.delete(id);
    redirect.addFlashAttribute("toast", "Профиль удалён");
    return "redirect:/import";
  }

  private String workspace(
      Model model,
      Uploads.Upload upload,
      TableFile table,
      ImportMapping mapping,
      boolean fragment) {
    Importer.Preview preview = importer.preview(table, mapping);
    model.addAttribute("upload", upload);
    model.addAttribute("table", table);
    model.addAttribute("rawRows", table.rows().stream().limit(RAW_ROWS).toList());
    model.addAttribute("columns", table.columns());
    model.addAttribute("columnLabels", columnLabels(table, mapping));
    model.addAttribute("mapping", mapping);
    model.addAttribute("preview", preview);
    model.addAttribute("previewRows", preview.rows().stream().limit(PREVIEW_ROWS).toList());
    model.addAttribute("accounts", accounts.active());
    model.addAttribute("categories", categories.all());
    model.addAttribute("statuses", Importer.Status.values());
    return fragment ? "importing/mapping :: workspace" : "importing/mapping";
  }

  private static TableFile read(Uploads.Upload upload, ImportMapping mapping) {
    return TableFile.read(
        upload.fileName(), upload.content(), mapping.getEncoding(), mapping.delimiterChar());
  }

  private static String expired(RedirectAttributes redirect) {
    redirect.addFlashAttribute("toast", "Файл больше не в памяти — загрузите его ещё раз");
    redirect.addFlashAttribute("toastLevel", "danger");
    return "redirect:/import";
  }

  /** «0 · Дата операции»: номер колонки и её заголовок, если он есть. */
  static List<String> columnLabels(TableFile table, ImportMapping mapping) {
    List<String> header =
        mapping.getHeaderRow() < table.rows().size()
            ? table.rows().get(mapping.getHeaderRow())
            : List.of();
    List<String> labels = new java.util.ArrayList<>();
    for (int i = 0; i < table.columns(); i++) {
      String title = i < header.size() ? header.get(i) : "";
      labels.add(
          (i + 1)
              + (title.isBlank()
                  ? ""
                  : " · " + (title.length() > 40 ? title.substring(0, 40) + "…" : title)));
    }
    return labels;
  }

  /** Номера колонок 0…n−1 для выпадающих списков. */
  static List<Integer> range(int n) {
    return java.util.stream.IntStream.range(0, n).boxed().toList();
  }
}
