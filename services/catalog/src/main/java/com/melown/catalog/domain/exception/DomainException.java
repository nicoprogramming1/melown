package com.melown.catalog.domain.exception;

public abstract class DomainException extends RuntimeException {

  protected DomainException(String message) {
    super(message);
  }
}
