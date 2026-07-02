package com.melown.catalog.domain.exception;

public class ConflictException extends DomainException {
    public ConflictException() {
        super("Conflict");
    }
}
