package com.melown.catalog.domain.model.condition;

import com.melown.catalog.domain.model.enums.ConditionGrade;
import java.util.Objects;

public record UsedCondition(String usedDescription, ConditionGrade usedConditionGrade)
    implements ProductCondition {

  private static final int MAX_LENGTH = 100;

  public UsedCondition {
    Objects.requireNonNull(usedDescription, "Descripción nula");
    Objects.requireNonNull(usedConditionGrade, "Condition grade nulo");
    usedDescription = usedDescription.strip();
    if (usedDescription.isBlank()) throw new IllegalArgumentException("Descripción vacía");
    if (usedDescription.length() > MAX_LENGTH)
      throw new IllegalArgumentException("Descripción muy larga");
  }
}
