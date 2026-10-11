package com.spendings.fx;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/** API курсов Национального банка Республики Беларусь: https://www.nbrb.by/apihelp/exrates */
@HttpExchange("/exrates")
interface NbrbApi {

  /** Курсы всех валют на дату; {@code periodicity=0} — ежедневные. */
  @GetExchange("/rates?periodicity=0")
  List<DailyRate> rates(
      @RequestParam("ondate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate onDate);

  /** История одной валюты, не длиннее 365 дней. Масштаба в ответе нет — он из {@link #rates}. */
  @GetExchange("/rates/dynamics/{id}")
  List<HistoryRate> dynamics(
      @PathVariable int id,
      @RequestParam("startdate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam("enddate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to);

  record DailyRate(
      @JsonProperty("Cur_ID") int id,
      @JsonProperty("Date") LocalDateTime date,
      @JsonProperty("Cur_Abbreviation") String code,
      @JsonProperty("Cur_Scale") int scale,
      @JsonProperty("Cur_OfficialRate") BigDecimal rate) {}

  record HistoryRate(
      @JsonProperty("Cur_ID") int id,
      @JsonProperty("Date") LocalDateTime date,
      @JsonProperty("Cur_OfficialRate") BigDecimal rate) {}
}
