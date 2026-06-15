package com.melown.catalog;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Disabled(
    "Re-habilitar cuando exista application.yaml con datasource (Flyway + Testcontainers)."
        + " Ver HANDOFF.md roadmap paso 3.")
class CatalogApplicationTests {

  @Test
  void contextLoads() {}
}
