package com.melown.catalog.infrastructure.adapter.out.persistence;

import com.melown.catalog.application.port.out.ProductRepository;
import com.melown.catalog.domain.model.snapshots.ProductSnapshot;
import com.melown.catalog.infrastructure.adapter.out.persistence.entities.ProductEntity;
import com.melown.catalog.infrastructure.adapter.out.persistence.mappers.ProductMapper;
import java.util.UUID;

public class ProductRepositoryImpl implements ProductRepository {

  private final ProductJPARepository productJPARepository;

  ProductRepositoryImpl(ProductJPARepository productJPARepository) {
    this.productJPARepository = productJPARepository;
  }

  @Override
  public UUID save(ProductSnapshot snapshot) {
    ProductEntity productEntity = ProductMapper.toEntity(snapshot);
    productJPARepository.save(productEntity);
    return productEntity.getId();
  }
}
