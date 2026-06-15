package com.melown.catalog.domain.model.status;

import java.time.Instant;
import java.util.Objects;

public record PublishedStatus(Instant publishedAt) implements ProductStatus {

    public PublishedStatus {
        Objects.requireNonNull(publishedAt, "publishedAt required");
    }

    public Instant publishedAt() {
        return publishedAt;
    }

    @Override
    public boolean isVisibleInCatalog() {
        return true;
    }

    @Override
    public boolean allowsPurchase() {
        return true;
    }
}
