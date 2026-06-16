package com.melown.catalog.infrastructure.grpc;

import com.melown.catalog.v1.CatalogServiceGrpc;
import net.devh.boot.grpc.server.service.GrpcService;

@GrpcService
public class CatalogGrpcService extends CatalogServiceGrpc.CatalogServiceImplBase {}
