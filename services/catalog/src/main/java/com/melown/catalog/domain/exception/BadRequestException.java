package com.melown.catalog.domain.exception;

public class BadRequestException extends DomainException {
  public BadRequestException() {
    super("Bad request");
  }
}
