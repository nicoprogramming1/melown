package com.melown.catalog.application.port.in;

import com.melown.catalog.domain.model.enums.ConditionGrade;

import java.util.Map;

public record CreateUsedProductCommand(
        String title,
        String description,
        Map<String, String> specifications,
        String usedDescription,
        ConditionGrade conditionGrade) implements CreateProductCommandKind {
}
