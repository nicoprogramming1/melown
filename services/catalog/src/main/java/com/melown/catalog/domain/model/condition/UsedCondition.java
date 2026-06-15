package com.melown.catalog.domain.model.condition;

import com.melown.catalog.domain.model.enums.ConditionGrade;

public record UsedCondition(String usedDescription, ConditionGrade usedConditionGrade)
    implements ProductCondition {}
