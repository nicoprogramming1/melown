package com.melown.catalog.application.port.in;

import com.melown.catalog.domain.model.enums.ConditionGrade;

public record CreateProductCommand(
    String name,
    String description,
    boolean guarantee,
    String usedDescription,
    ConditionGrade conditionGrade) {}
