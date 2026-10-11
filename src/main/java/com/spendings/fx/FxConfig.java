package com.spendings.fx;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration
@EnableResilientMethods
class FxConfig {

  @Bean
  NbrbApi nbrbApi(
      RestClient.Builder builder, @Value("${spendings.fx.nbrb.base-url}") String baseUrl) {
    RestClient client = builder.baseUrl(baseUrl).build();
    return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(client))
        .build()
        .createClient(NbrbApi.class);
  }

  @Bean
  RateProvider rateProvider(NbrbApi api) {
    return new NbrbRateProvider(api);
  }

  @Bean
  Clock clock() {
    return Clock.systemDefaultZone();
  }

  @Bean
  RateSync rateSync(
      RateProvider provider,
      RateDemand demand,
      Currencies currencies,
      JdbcClient jdbc,
      Clock clock) {
    return new RateSync(provider, demand, currencies, jdbc, clock);
  }

  /** Загрузка по расписанию и при старте. В тестах выключена: там нет доступа к НБРБ. */
  @Configuration
  @EnableScheduling
  @EnableAsync
  @ConditionalOnBooleanProperty(name = "spendings.fx.sync.enabled", matchIfMissing = true)
  static class Schedule {

    private static final Logger log = LoggerFactory.getLogger(Schedule.class);

    private final RateSync sync;

    Schedule(RateSync sync) {
      this.sync = sync;
    }

    @Async
    @EventListener(ApplicationReadyEvent.class)
    void onStartup() {
      run();
    }

    @Scheduled(cron = "${spendings.fx.sync.cron}", zone = "${spendings.fx.sync.zone}")
    void run() {
      try {
        sync.sync();
      } catch (RuntimeException e) {
        log.warn("Курсы не загружены: {}", e.getMessage());
      }
    }
  }
}
