package com.melown.catalog.domain.model;

import com.melown.catalog.domain.exception.BadRequestException;
import java.util.Map;

public record Specifications(Map<String, String> values) {

  public Specifications {
    if (values == null || values.isEmpty()) throw new BadRequestException();
    values = Map.copyOf(values);
  }

  public static Specifications create(Map<String, String> values) {
    return new Specifications(values);
  }
}
