package com.melown.catalog.domain.model.status;

import java.time.Instant;
import java.util.Objects;

public record PublishedStatus(Instant publishedAt) implements ProductStatus {

    public PublishedStatus {
        Objects.requireNonNull(publishedAt, "publishedAt required");
        if (publishedAt.isAfter(Instant.now()))
            throw new IllegalArgumentException("La fecha no puede ser después de hoy");
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
