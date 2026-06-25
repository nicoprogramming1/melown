package com.melown.catalog.application.usecase;

import com.melown.catalog.application.port.in.CreateProductCommandKind;
import com.melown.catalog.application.port.out.ProductRepository;
import com.melown.catalog.common.helpers.ProductConstructor;
import com.melown.catalog.domain.model.Product;
import com.melown.catalog.domain.model.ProductId;
import com.melown.catalog.domain.policy.CatalogPolicy;
import com.melown.catalog.infrastructure.adapter.out.dto.CreateProductResult;
import com.melown.catalog.infrastructure.adapter.out.persistence.mappers.ProductMapper;
import com.melown.catalog.infrastructure.config.ClockConfig;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class CreateProductUseCase {

    private final ProductRepository repository;
    private final ClockConfig clock;
    private final CatalogPolicy policy;

    CreateProductUseCase(
            ProductRepository repository,
            ClockConfig clock,
            CatalogPolicy policy
    ) {
        this.repository = repository;
        this.clock = clock;
        this.policy = policy;
    }

    public CreateProductResult handle(CreateProductCommandKind createProductCommandKind) {
        Instant expiredDraftDate = Instant.now(clock.now()).plus(policy.draftTTL());
        Product product = ProductConstructor.build(createProductCommandKind, clock.now(), expiredDraftDate);
        ProductId savedProductId = repository.save(product.toSnapshot());
        return ProductMapper.toResultCreate(savedProductId);
    }
}
