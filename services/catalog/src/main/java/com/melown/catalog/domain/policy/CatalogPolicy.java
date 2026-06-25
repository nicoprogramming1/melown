package com.melown.catalog.domain.policy;

import java.time.Duration;
import java.util.Objects;

public record CatalogPolicy(Duration draftTTL) {
    public CatalogPolicy {
        Objects.requireNonNull(draftTTL, "El draftTTL es null");
        if (draftTTL.isNegative() | draftTTL.isZero())
            throw new IllegalArgumentException("La duración del draft debe ser mayor a cero");
    }
}
