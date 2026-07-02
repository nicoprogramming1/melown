package com.melown.catalog.domain.exception;

import com.melown.catalog.domain.model.ProductId;

public class NotFoundException extends DomainException {
  public NotFoundException(ProductId id) {
    super("Product not found: " + id.value());
  }
}
