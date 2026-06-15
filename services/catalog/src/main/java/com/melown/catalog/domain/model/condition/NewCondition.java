package com.melown.catalog.domain.model.condition;

public record NewCondition(boolean guarantee) implements ProductCondition {
  public boolean guarantee() {
    return guarantee;
  }
}
