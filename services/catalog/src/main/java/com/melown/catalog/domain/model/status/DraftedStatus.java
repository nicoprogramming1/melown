package com.melown.catalog.domain.model.status;

import java.time.Instant;
import java.util.Objects;

public record DraftedStatus(Instant expirationAt) implements ProductStatus {

    public DraftedStatus {
        Objects.requireNonNull(expirationAt, "expirationAt required");
    }

    public boolean isExpired(Instant now) {
        return expirationAt.isBefore(now);
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
