package com.melown.catalog.infrastructure.adapter.out.persistence;

import com.melown.catalog.application.port.out.ProductRepository;
import com.melown.catalog.domain.exception.NotFoundException;
import com.melown.catalog.domain.model.Product;
import com.melown.catalog.domain.model.ProductId;
import com.melown.catalog.domain.model.snapshots.ProductSnapshot;
import com.melown.catalog.infrastructure.adapter.out.persistence.entities.ProductEntity;
import com.melown.catalog.infrastructure.adapter.out.persistence.mappers.ProductMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ProductRepositoryImpl implements ProductRepository {

    private final ProductJPARepository productJPARepository;

    ProductRepositoryImpl(ProductJPARepository productJPARepository) {
        this.productJPARepository = productJPARepository;
    }

    @Override
    public ProductId save(ProductSnapshot snapshot) {
        ProductEntity productEntity = ProductMapper.toEntity(snapshot);
        ProductEntity savedEntity = productJPARepository.save(productEntity);
        return ProductMapper.toProductId(savedEntity.getId());
    }

    @Override
    public Product retrieve(ProductId publicId) {
        ProductEntity entity = productJPARepository.findByPublicId(publicId.value()).orElseThrow(() -> new NotFoundException(publicId));
        return ProductMapper.toDomain(entity);
    }
}
