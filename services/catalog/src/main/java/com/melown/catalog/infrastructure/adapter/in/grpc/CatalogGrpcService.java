package com.melown.catalog.infrastructure.adapter.in.grpc;

import com.melown.catalog.application.port.in.CreateProductCommandKind;
import com.melown.catalog.application.port.in.GetProductQuery;
import com.melown.catalog.application.usecase.CreateProductUseCase;
import com.melown.catalog.application.usecase.GetProductUseCase;
import com.melown.catalog.infrastructure.adapter.out.dto.CreateProductResult;
import com.melown.catalog.infrastructure.adapter.out.dto.GetProductResult;
import com.melown.catalog.v1.*;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

@GrpcService
public class CatalogGrpcService extends CatalogServiceGrpc.CatalogServiceImplBase {

  private final CreateProductUseCase createProductUseCase;
  private final GetProductUseCase getProductUseCase;

  CatalogGrpcService(
      CreateProductUseCase createProductUseCase, GetProductUseCase getProductUseCase) {
    this.createProductUseCase = createProductUseCase;
    this.getProductUseCase = getProductUseCase;
  }

  @Override
  public void createProduct(
      CreateProductRequest request, StreamObserver<CreateProductResponse> responseObserver) {
    // el kind define si el product es new / used
    CreateProductCommandKind command = CatalogGrpcMapper.toCommandCreate(request);
    CreateProductResult result = createProductUseCase.handle(command);
    CreateProductResponse response = CatalogGrpcMapper.toResponseCreate(result.getId().value());
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  public void getProduct(
      GetProductRequest request, StreamObserver<GetProductResponse> responseObserver) {
    GetProductQuery query = CatalogGrpcMapper.toQueryGet(request);
    GetProductResult result = getProductUseCase.handle(query);
    GetProductResponse response = CatalogGrpcMapper.toResponseGet(result);
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }
}
