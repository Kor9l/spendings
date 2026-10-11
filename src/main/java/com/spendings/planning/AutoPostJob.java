package com.spendings.planning;

import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Каждое утро (и при старте) проводит наступившие платежи правил с автопроведением. */
@Component
@ConditionalOnBooleanProperty(name = "spendings.planning.autopost.enabled", matchIfMissing = true)
class AutoPostJob {

  private static final Logger log = LoggerFactory.getLogger(AutoPostJob.class);

  private final Planner planner;

  AutoPostJob(Planner planner) {
    this.planner = planner;
  }

  @Async
  @EventListener(ApplicationReadyEvent.class)
  void onStartup() {
    run();
  }

  @Scheduled(
      cron = "${spendings.planning.autopost.cron:0 0 6 * * *}",
      zone = "${spendings.fx.sync.zone}")
  void run() {
    try {
      int posted = planner.autoPost(LocalDate.now());
      if (posted > 0) {
        log.info("Автоматически проведено регулярных операций: {}", posted);
      }
    } catch (RuntimeException e) {
      log.warn("Автопроведение не удалось: {}", e.getMessage());
    }
  }
}
