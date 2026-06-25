package com.melown.catalog.domain.model.status;

import java.util.Objects;
import java.util.UUID;

public record PausedStatus(UUID pausedBy) implements ProductStatus {
    // Constante para el UUID vacío (Nil)
    private static final UUID NIL_UUID = new UUID(0L, 0L);

    public PausedStatus {
        Objects.requireNonNull(pausedBy, "pausedBy required");
        if (pausedBy.equals(NIL_UUID)) throw new IllegalArgumentException("No puede ser un uuid en cero");
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
