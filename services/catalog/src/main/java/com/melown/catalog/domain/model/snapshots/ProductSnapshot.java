package com.melown.catalog.domain.model.snapshots;

import com.melown.catalog.domain.model.ProductId;
import com.melown.catalog.domain.model.Specifications;
import com.melown.catalog.domain.model.condition.ProductCondition;
import com.melown.catalog.domain.model.status.ProductStatus;

import java.time.Instant;
import java.util.UUID;

public record ProductSnapshot(
        ProductId id,
        String name,
        String description,
        Specifications specifications,
        UUID vendorId,
        ProductCondition productCondition,   // ← sealed, tipo rico
        ProductStatus productStatus,        // ← sealed, tipo rico
        Long version,
        Instant createdAt,
        Instant updatedAt
) {
}
