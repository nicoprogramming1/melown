package com.melown.catalog.domain.model.specs;

import com.melown.catalog.domain.model.Specifications;
import com.melown.catalog.domain.model.enums.ConditionGrade;
import java.util.UUID;

public record UsedProductSpec(
    String title,
    String description,
    Specifications specifications,
    UUID vendorId,
    String usedDescription,
    ConditionGrade conditionGrade,
    Long version) {}
