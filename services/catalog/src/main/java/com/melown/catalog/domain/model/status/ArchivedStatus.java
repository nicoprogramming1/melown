package com.melown.catalog.domain.model.status;

import java.time.Instant;
import java.util.Objects;

public record ArchivedStatus(Instant archivedAt, String reason) implements ProductStatus {

    public ArchivedStatus {
        Objects.requireNonNull(archivedAt, "archivedAt required");
        Objects.requireNonNull(reason, "reason required");
    }

    public Instant archivedAt() {
        return archivedAt;
    }

    public String reason() {
        return reason;
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
