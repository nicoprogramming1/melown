package com.melown.catalog.domain.model.specs;

import com.melown.catalog.domain.model.Specifications;
import com.melown.catalog.domain.model.condition.ProductCondition;
import com.melown.catalog.domain.model.status.ProductStatus;
import java.util.UUID;

public record RehydrateProductSpec(
    UUID id,
    String name,
    String description,
    Specifications specifications,
    UUID vendorId,
    String usedDescription,
    ProductCondition productCondition,
    ProductStatus productStatus) {}
