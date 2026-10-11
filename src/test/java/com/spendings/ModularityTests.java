package com.spendings;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

  @Test
  void modulesRespectTheirBoundaries() {
    ApplicationModules.of(SpendingsApplication.class).verify();
  }
}
