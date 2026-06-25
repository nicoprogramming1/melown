package com.melown.catalog.application.usecase;

import com.melown.catalog.application.port.in.CreateProductCommandKind;
import com.melown.catalog.application.port.out.ProductRepository;
import com.melown.catalog.common.helpers.ProductConstructor;
import com.melown.catalog.domain.model.Product;
import com.melown.catalog.domain.model.ProductId;
import com.melown.catalog.infrastructure.adapter.out.dto.CreateProductResult;
import com.melown.catalog.infrastructure.adapter.out.persistence.mappers.ProductMapper;
import com.melown.catalog.infrastructure.config.ClockConfig;
import org.springframework.stereotype.Service;

@Service
public class CreateProductUseCase {

    private final ProductRepository repository;
    private final ClockConfig clock;

    CreateProductUseCase(
            ProductRepository repository,
            ClockConfig clock
    ) {
        this.repository = repository;
        this.clock = clock;
    }

    public CreateProductResult handle(CreateProductCommandKind createProductCommandKind) {
        Product product = ProductConstructor.build(createProductCommandKind, clock.now());
        ProductId savedProductId = repository.save(product.toSnapshot());
        return ProductMapper.toResultCreate(savedProductId);
    }
}
