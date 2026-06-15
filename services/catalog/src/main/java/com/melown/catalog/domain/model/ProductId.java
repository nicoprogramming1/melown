package com.melown.catalog.domain.model;

import java.util.UUID;

public record ProductId(UUID value) {

  public static ProductId create() {
    return new ProductId(UUID.randomUUID());
  }

  public static ProductId reconstitute(UUID id) {

    return new ProductId(id);
  }
}
