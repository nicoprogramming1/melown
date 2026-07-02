package com.melown.catalog.domain.model.snapshots;

import com.melown.catalog.domain.model.Auditory;
import com.melown.catalog.domain.model.ProductId;
import com.melown.catalog.domain.model.Specifications;
import com.melown.catalog.domain.model.condition.ProductCondition;
import com.melown.catalog.domain.model.status.ProductStatus;

public record ProductSnapshot(
    ProductId id,
    String title,
    String description,
    Specifications specifications,
    ProductId vendorId,
    ProductCondition productCondition, // ← sealed, tipo rico
    ProductStatus productStatus, // ← sealed, tipo rico
    Auditory auditory) {}
