package com.melown.catalog.domain.model.condition;

public sealed interface ProductCondition permits NewCondition, UsedCondition {
}
