package com.melown.catalog.infrastructure.adapter.out.persistence;

import com.melown.catalog.infrastructure.adapter.out.persistence.entities.ProductEntity;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductJPARepository extends JpaRepository<ProductEntity, UUID> {

    Optional<ProductEntity> findByPublicId(UUID publicId);
}
