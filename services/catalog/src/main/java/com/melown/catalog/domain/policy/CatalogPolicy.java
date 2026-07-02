package com.melown.catalog.domain.policy;

import java.time.Duration;
import java.util.Objects;

public record CatalogPolicy(Duration draftTtl) {
  public CatalogPolicy {
    Objects.requireNonNull(draftTtl, "El draftTtl es null");
    if (draftTtl.isNegative() || draftTtl.isZero())
      throw new IllegalArgumentException("La duración del draft debe ser mayor a cero");
  }
}
