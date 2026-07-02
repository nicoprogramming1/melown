package com.melown.catalog.infrastructure.adapter.in.grpc;

import com.melown.catalog.domain.exception.BadRequestException;
import com.melown.catalog.domain.exception.NotFoundException;
import net.devh.boot.grpc.server.advice.GrpcAdvice;
import net.devh.boot.grpc.server.advice.GrpcExceptionHandler;
import io.grpc.Status;

@GrpcAdvice
public class CatalopgGrpcAdvice {

    @GrpcExceptionHandler(BadRequestException.class)
    public Status handleBadRequest(BadRequestException ex) {
        return Status.INVALID_ARGUMENT.withDescription(ex.getMessage());
    }

    @GrpcExceptionHandler(NotFoundException.class)
    public Status handleNotFound(NotFoundException ex) {
        return Status.NOT_FOUND.withDescription(ex.getMessage());
    }

    @GrpcExceptionHandler(Throwable.class)
    public Status handleInternal(Throwable ex) {
        return Status.INTERNAL.withDescription("Internal server error");
    }
}
