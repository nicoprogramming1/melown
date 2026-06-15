package com.melown.catalog.domain.model.status;

import java.util.Objects;
import java.util.UUID;

public record PausedStatus(UUID pausedBy) implements ProductStatus {

    public PausedStatus {
        Objects.requireNonNull(pausedBy, "pausedBy required");
    }

    public UUID pausedBy() {
        return pausedBy;
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
