package com.spendings.demo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Заполняет пустую базу демо-данными при старте: {@code SPENDINGS_DEMO_OPERATIONS=50000 ./gradlew
 * bootRun}. Владельца по-прежнему создаёт мастер первого запуска.
 */
@Component
@ConditionalOnProperty("spendings.demo.operations")
class DemoDataRunner implements ApplicationRunner {

  private final DemoData demo;
  private final int operations;

  DemoDataRunner(DemoData demo, @Value("${spendings.demo.operations}") int operations) {
    this.demo = demo;
    this.operations = operations;
  }

  @Override
  public void run(ApplicationArguments args) {
    demo.generate(operations);
  }
}
