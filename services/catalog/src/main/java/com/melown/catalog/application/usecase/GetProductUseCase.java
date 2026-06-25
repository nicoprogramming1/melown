package com.melown.catalog.application.usecase;

import com.melown.catalog.application.port.in.GetProductQuery;
import com.melown.catalog.application.port.out.ProductRepository;
import com.melown.catalog.domain.model.Product;
import com.melown.catalog.domain.model.snapshots.ProductSnapshot;
import com.melown.catalog.infrastructure.adapter.out.dto.GetProductResult;
import com.melown.catalog.infrastructure.adapter.out.persistence.mappers.ProductMapper;
import org.springframework.stereotype.Service;

@Service
public class GetProductUseCase {

    private final ProductRepository productRepository;

    GetProductUseCase(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public GetProductResult handle(GetProductQuery request) {
        Product product = productRepository.retrieve(ProductMapper.toProductId(request.id()));
        ProductSnapshot snapshot = product.toSnapshot();
        return ProductMapper.toResultGet(snapshot);
    }
}
