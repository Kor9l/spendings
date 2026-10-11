package com.spendings.fx;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Настройки: базовая валюта и курсы. */
@Controller
class SettingsController {

  private final BaseCurrency baseCurrency;
  private final Currencies currencies;
  private final ExchangeRates rates;
  private final RateSync sync;
  private final RateProvider provider;

  SettingsController(
      BaseCurrency baseCurrency,
      Currencies currencies,
      ExchangeRates rates,
      RateSync sync,
      RateProvider provider) {
    this.baseCurrency = baseCurrency;
    this.currencies = currencies;
    this.rates = rates;
    this.sync = sync;
    this.provider = provider;
  }

  @GetMapping("/settings")
  String settings(Model model) {
    model.addAttribute("baseCurrency", baseCurrency.code());
    model.addAttribute("currencies", currencies.all());
    model.addAttribute("rates", rates.latest());
    model.addAttribute("pivot", provider.pivot());
    model.addAttribute("today", LocalDate.now());
    return "fx/settings";
  }

  @PostMapping("/settings/base-currency")
  String changeBaseCurrency(@RequestParam String code, RedirectAttributes redirect) {
    baseCurrency.change(code);
    redirect.addFlashAttribute("toast", "Итоги теперь считаются в " + code);
    return "redirect:/settings";
  }

  @PostMapping("/settings/rates")
  String saveRate(
      @RequestParam String currency,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @RequestParam BigDecimal rate,
      RedirectAttributes redirect) {
    if (rate.signum() <= 0 || currency.equals(provider.pivot())) {
      redirect.addFlashAttribute(
          "toast", "Курс должен быть больше нуля и не для " + provider.pivot());
      redirect.addFlashAttribute("toastLevel", "danger");
      return "redirect:/settings";
    }
    rates.saveManual(currency, provider.pivot(), date, rate);
    redirect.addFlashAttribute("toast", "Курс " + currency + " на " + date + " сохранён");
    return "redirect:/settings";
  }

  @PostMapping("/settings/rates/sync")
  String syncNow(RedirectAttributes redirect) {
    try {
      int added = sync.sync();
      redirect.addFlashAttribute(
          "toast", added == 0 ? "Курсы уже актуальны" : "Загружено курсов: " + added);
    } catch (RuntimeException e) {
      redirect.addFlashAttribute("toast", "Не удалось загрузить курсы: " + e.getMessage());
      redirect.addFlashAttribute("toastLevel", "danger");
    }
    return "redirect:/settings";
  }
}
