package com.melown.catalog.domain.model.status;

import java.time.Instant;
import java.util.Objects;

public record DraftedStatus(Instant expirationAt) implements ProductStatus {

  public DraftedStatus {
    Objects.requireNonNull(expirationAt, "expirationAt required");
  }

  public Instant expirationAt() {
    return expirationAt;
  }

  public boolean isExpired() {
    return expirationAt.isBefore(Instant.now());
  }

  @Override
  public boolean isVisibleInCatalog() {
    return false;
  }

  @Override
  public boolean allowsPurchase() {
    return false;
  }
}
