package com.melown.catalog.application.usecase;

import com.melown.catalog.application.port.in.GetProductQuery;
import com.melown.catalog.application.port.out.ProductRepository;
import com.melown.catalog.domain.model.Product;
import com.melown.catalog.domain.model.snapshots.ProductSnapshot;
import com.melown.catalog.domain.model.status.DraftedStatus;
import com.melown.catalog.infrastructure.adapter.out.dto.GetProductResult;
import com.melown.catalog.infrastructure.adapter.out.persistence.mappers.ProductMapper;
import com.melown.catalog.infrastructure.config.ClockConfig;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class GetProductUseCase {

    private final ProductRepository productRepository;
    private final ClockConfig clock;

    GetProductUseCase(ProductRepository productRepository, ClockConfig clock) {
        this.productRepository = productRepository;
        this.clock = clock;
    }

    public GetProductResult handle(GetProductQuery request) {
        Instant now = clock.now();
        Product product = productRepository.retrieve(ProductMapper.toProductId(request.id()));
        ProductSnapshot snapshot = product.toSnapshot();
        boolean isExpired = snapshot.productStatus() instanceof DraftedStatus d && d.isExpired(now);
        return ProductMapper.toResultGet(snapshot, isExpired);
    }
}
