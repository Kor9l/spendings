package com.spendings.category;

import com.spendings.shared.DomainException;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/categories")
class CategoryController {

  private final Categories categories;
  private final MessageSource messages;

  CategoryController(Categories categories, MessageSource messages) {
    this.categories = categories;
    this.messages = messages;
  }

  @GetMapping
  String tree(@RequestParam(defaultValue = "EXPENSE") CategoryKind kind, Model model) {
    model.addAttribute("kind", kind);
    model.addAttribute("tree", categories.tree(kind, true));
    return "category/tree";
  }

  @GetMapping("/new")
  String newForm(
      @RequestParam(defaultValue = "EXPENSE") CategoryKind kind,
      @RequestParam(required = false) @Nullable UUID parentId,
      Model model) {
    var form = new CategoryForm();
    form.setKind(kind);
    form.setParentId(parentId);
    return form(model, form, null);
  }

  @PostMapping
  String create(
      @Valid @ModelAttribute("form") CategoryForm form,
      BindingResult errors,
      Model model,
      RedirectAttributes redirect) {
    if (errors.hasErrors()) {
      return form(model, form, null);
    }
    try {
      categories.create(form);
    } catch (DomainException e) {
      errors.reject(e.messageKey(), e.arguments(), null);
      return form(model, form, null);
    }
    redirect.addFlashAttribute("toast", "Категория «" + form.getName().strip() + "» создана");
    return "redirect:/categories?kind=" + form.getKind();
  }

  @GetMapping("/{id}/edit")
  String editForm(@PathVariable UUID id, Model model) {
    return form(model, categories.formFor(id), id);
  }

  @PostMapping("/{id}")
  String update(
      @PathVariable UUID id,
      @Valid @ModelAttribute("form") CategoryForm form,
      BindingResult errors,
      Model model,
      RedirectAttributes redirect) {
    CategoryKind kind = categories.get(id).kind();
    form.setKind(kind);
    if (errors.hasErrors()) {
      return form(model, form, id);
    }
    try {
      categories.update(id, form);
    } catch (DomainException e) {
      errors.reject(e.messageKey(), e.arguments(), null);
      return form(model, form, id);
    }
    redirect.addFlashAttribute("toast", "Категория сохранена");
    return "redirect:/categories?kind=" + kind;
  }

  @PostMapping("/{id}/archive")
  String archive(@PathVariable UUID id, RedirectAttributes redirect) {
    CategoryKind kind = categories.get(id).kind();
    categories.archive(id);
    redirect.addFlashAttribute("toast", "Категория перенесена в архив");
    return "redirect:/categories?kind=" + kind;
  }

  @PostMapping("/{id}/restore")
  String restore(@PathVariable UUID id, RedirectAttributes redirect) {
    CategoryKind kind = categories.get(id).kind();
    categories.restore(id);
    redirect.addFlashAttribute("toast", "Категория возвращена из архива");
    return "redirect:/categories?kind=" + kind;
  }

  @PostMapping("/{id}/delete")
  String delete(@PathVariable UUID id, RedirectAttributes redirect) {
    CategoryKind kind = categories.get(id).kind();
    act(redirect, () -> categories.delete(id), "Категория удалена");
    return "redirect:/categories?kind=" + kind;
  }

  @PostMapping("/{id}/merge")
  String merge(@PathVariable UUID id, @RequestParam UUID targetId, RedirectAttributes redirect) {
    CategoryRef source = categories.get(id);
    CategoryRef target = categories.get(targetId);
    act(
        redirect,
        () -> categories.merge(id, targetId),
        "«" + source.displayName() + "» объединена с «" + target.displayName() + "»");
    return "redirect:/categories?kind=" + source.kind();
  }

  @PostMapping("/reorder")
  ResponseEntity<Void> reorder(@RequestParam("id") List<UUID> ids) {
    categories.reorder(ids);
    return ResponseEntity.noContent().build();
  }

  private void act(RedirectAttributes redirect, Runnable action, String success) {
    try {
      action.run();
      redirect.addFlashAttribute("toast", success);
    } catch (DomainException e) {
      redirect.addFlashAttribute(
          "toast",
          messages.getMessage(e.messageKey(), e.arguments(), LocaleContextHolder.getLocale()));
      redirect.addFlashAttribute("toastLevel", "danger");
    }
  }

  private String form(Model model, CategoryForm form, @Nullable UUID id) {
    model.addAttribute("form", form);
    model.addAttribute("categoryId", id);
    List<CategoryRef> parents =
        categories.tree(form.getKind(), false).stream()
            .map(Categories.CategoryNode::category)
            .filter(c -> !c.id().equals(id))
            .toList();
    model.addAttribute("parents", parents);
    if (id != null) {
      model.addAttribute("category", categories.get(id));
      model.addAttribute(
          "mergeTargets",
          categories.all().values().stream()
              .filter(c -> c.kind() == form.getKind() && !c.archived())
              .filter(c -> !c.id().equals(id) && !id.equals(c.parentId()))
              .toList());
    }
    return "category/form";
  }
}
