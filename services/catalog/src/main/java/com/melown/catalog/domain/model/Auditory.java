package com.melown.catalog.domain.model;

import java.time.Instant;
import java.util.Objects;

public record Auditory(Long version, Instant createdAt, Instant updatedAt) {
    public Auditory {
        Objects.requireNonNull(version, "Version es necesario");
        Objects.requireNonNull(createdAt, "CreatedAt es necesario");
        Objects.requireNonNull(updatedAt, "UpdatedAt es necesario");
        if (version < 0L) throw new IllegalArgumentException("Version debe ser positivo");
        if (updatedAt.isBefore(createdAt))
            throw new IllegalArgumentException("Updated no puede ser menor a created");
    }
}
