package com.spendings.importing;

import com.spendings.category.Categories;
import com.spendings.category.CategoryKind;
import com.spendings.category.CategoryRef;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/import/rules")
class RulesController {

  private final CategorizationRules rules;
  private final Categories categories;

  RulesController(CategorizationRules rules, Categories categories) {
    this.rules = rules;
    this.categories = categories;
  }

  @GetMapping
  String list(Model model) {
    model.addAttribute("rules", rules.all());
    model.addAttribute("categories", categories.all());
    List<CategoryRef> options = new ArrayList<>();
    for (CategoryKind kind : CategoryKind.values()) {
      for (Categories.CategoryNode node : categories.tree(kind, false)) {
        options.add(node.category());
        options.addAll(node.children());
      }
    }
    model.addAttribute("options", options);
    return "importing/rules";
  }

  @PostMapping
  String add(
      @RequestParam String pattern, @RequestParam UUID categoryId, RedirectAttributes redirect) {
    CategoryRef category = categories.get(categoryId);
    if (pattern.isBlank()) {
      redirect.addFlashAttribute("toast", "Укажите текст, который ищем в описании");
      redirect.addFlashAttribute("toastLevel", "danger");
      return "redirect:/import/rules";
    }
    rules.learn(pattern, categoryId, category.kind());
    redirect.addFlashAttribute(
        "toast", "Правило «" + pattern.strip() + "» → " + category.displayName() + " сохранено");
    return "redirect:/import/rules";
  }

  @PostMapping("/{id}/delete")
  String delete(@PathVariable UUID id, RedirectAttributes redirect) {
    rules.delete(id);
    redirect.addFlashAttribute("toast", "Правило удалено");
    return "redirect:/import/rules";
  }
}
