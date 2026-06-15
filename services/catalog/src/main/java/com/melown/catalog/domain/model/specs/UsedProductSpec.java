package com.melown.catalog.domain.model.specs;

import com.melown.catalog.domain.model.enums.ConditionGrade;
import com.melown.catalog.domain.model.Specifications;

import java.util.UUID;

public record UsedProductSpec(
        String name,
        String description,
        Specifications specifications,
        UUID vendorId,
        String usedDescription,
        ConditionGrade conditionGrade,
        Long version
) {
}
