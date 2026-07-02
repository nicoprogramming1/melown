package com.melown.catalog.infrastructure.adapter.in.grpc;

import com.melown.catalog.domain.exception.BadRequestException;
import com.melown.catalog.domain.exception.NotFoundException;
import net.devh.boot.grpc.server.advice.GrpcAdvice;
import net.devh.boot.grpc.server.advice.GrpcExceptionHandler;
import io.grpc.Status;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@GrpcAdvice
public class CatalopgGrpcAdvice {

    @GrpcExceptionHandler(BadRequestException.class)
    public Status handleBadRequest(BadRequestException ex) {
        return Status.INVALID_ARGUMENT.withDescription(ex.getMessage());
    }

    @GrpcExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public Status handleOptimisticLocking(ObjectOptimisticLockingFailureException ex) {
        // log ex.getMessage();
        return Status.ABORTED.withDescription("resource modified concurrently, retry");
    }

    @GrpcExceptionHandler(NotFoundException.class)
    public Status handleNotFound(NotFoundException ex) {
        return Status.NOT_FOUND.withDescription(ex.getMessage());
    }

    @GrpcExceptionHandler(Throwable.class)
    public Status handleInternal(Throwable ex) {
        // TODO: loguear (sera mas tarde)
        return Status.INTERNAL.withDescription("Internal server error");
    }
}
