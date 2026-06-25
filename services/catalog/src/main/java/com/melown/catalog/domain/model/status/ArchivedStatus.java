package com.melown.catalog.domain.model.status;

import java.time.Instant;
import java.util.Objects;

public record ArchivedStatus(Instant archivedAt, String reason) implements ProductStatus {

    private static final int MIN_LENGTH_REASON = 5;
    private static final int MAX_LENGTH_REASON = 50;

    public ArchivedStatus {
        Objects.requireNonNull(archivedAt, "archivedAt required");
        Objects.requireNonNull(reason, "reason required");
        reason = reason.strip();
        if (reason.isBlank()) throw new IllegalArgumentException("No tiene contenido");
        if (reason.length() > MAX_LENGTH_REASON) throw new IllegalArgumentException("Muy largo");
        if (reason.length() < MIN_LENGTH_REASON) throw new IllegalArgumentException("Muy corto");
        if (archivedAt.isAfter(Instant.now())) throw new IllegalArgumentException("Fecha después de ahora");
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
