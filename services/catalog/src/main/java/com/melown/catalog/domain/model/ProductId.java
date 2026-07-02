package com.melown.catalog.domain.model;

import java.util.Objects;
import java.util.UUID;

public record ProductId(UUID value) {

  private static final UUID NIL_UUID = new UUID(0L, 0L);

  public ProductId {
    Objects.requireNonNull(value, "El uuid no puede ser null");
    if (value.equals(NIL_UUID)) throw new IllegalArgumentException("El uuid no puede ser ceros");
  }

  public static ProductId create() {
    return new ProductId(UUID.randomUUID());
  }

  public static ProductId reconstitute(UUID id) {
    return new ProductId(id);
  }
}
