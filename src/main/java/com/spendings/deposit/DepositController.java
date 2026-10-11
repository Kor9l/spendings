package com.spendings.deposit;

import com.spendings.shared.DomainException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tools.jackson.databind.json.JsonMapper;

/** Вклады: обзор, график начислений, проведение процентов, годовой отчёт. */
@Controller
@RequestMapping("/deposits")
class DepositController {

  private static final String[] MONTHS = {
    "янв", "фев", "мар", "апр", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек"
  };

  private final Deposits deposits;
  private final JsonMapper json;

  DepositController(Deposits deposits, JsonMapper json) {
    this.deposits = deposits;
    this.json = json;
  }

  @GetMapping
  String list(Model model) {
    var all = deposits.all();
    model.addAttribute(
        "active", all.stream().filter(d -> !d.deposit().account().archived()).toList());
    model.addAttribute(
        "archived", all.stream().filter(d -> d.deposit().account().archived()).toList());
    model.addAttribute("due", deposits.due());
    model.addAttribute("today", LocalDate.now());
    return "deposit/list";
  }

  @GetMapping("/{id}")
  String view(@PathVariable UUID id, Model model) {
    model.addAttribute("view", deposits.view(id));
    model.addAttribute(
        "due", deposits.due().stream().filter(d -> d.deposit().account().id().equals(id)).toList());
    model.addAttribute("today", LocalDate.now());
    return "deposit/view";
  }

  @GetMapping("/report")
  String report(@RequestParam(required = false) @Nullable Integer year, Model model) {
    int shown = year == null ? Year.now().getValue() : year;
    Deposits.YearReport report = deposits.year(shown);
    model.addAttribute("report", report);
    model.addAttribute("monthNames", MONTHS);
    model.addAttribute("chart", json.writeValueAsString(chart(report)));
    return "deposit/report";
  }

  @PostMapping("/{id}/post")
  String post(
      @PathVariable UUID id,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
      @RequestParam BigDecimal amount,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          @Nullable LocalDate date,
      @RequestParam(defaultValue = "/deposits") String back,
      RedirectAttributes redirect) {
    try {
      deposits.post(id, periodEnd, amount, date == null ? periodEnd : date);
      redirect.addFlashAttribute("toast", "Проценты проведены");
    } catch (DomainException e) {
      redirect.addFlashAttribute("toast", "Не удалось провести проценты: проверьте сумму");
      redirect.addFlashAttribute("toastLevel", "danger");
    }
    return "redirect:" + safe(back);
  }

  @PostMapping("/{id}/skip")
  String skip(
      @PathVariable UUID id,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
      @RequestParam(defaultValue = "/deposits") String back,
      RedirectAttributes redirect) {
    deposits.skip(id, periodEnd);
    redirect.addFlashAttribute("toast", "Начисление пропущено");
    return "redirect:" + safe(back);
  }

  @PostMapping("/{id}/unskip")
  String unskip(
      @PathVariable UUID id,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
      RedirectAttributes redirect) {
    deposits.unskip(id, periodEnd);
    redirect.addFlashAttribute("toast", "Начисление снова ожидается");
    return "redirect:/deposits/" + id;
  }

  private static Map<String, Object> chart(Deposits.YearReport report) {
    List<Map<String, Object>> series = new ArrayList<>();
    for (Deposits.YearRow row : report.rows()) {
      series.add(
          Map.of(
              "name",
              row.deposit().account().name(),
              "data",
              Arrays.stream(row.months()).map(BigDecimal::doubleValue).toList()));
    }
    return Map.of("type", "interest", "months", List.of(MONTHS), "series", series);
  }

  private static String safe(String back) {
    return back.startsWith("/") && !back.startsWith("//") ? back : "/deposits";
  }
}
