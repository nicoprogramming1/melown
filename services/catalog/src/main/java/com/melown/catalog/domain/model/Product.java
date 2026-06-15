package com.melown.catalog.domain.model;

import com.melown.catalog.domain.model.condition.NewCondition;
import com.melown.catalog.domain.model.condition.ProductCondition;
import com.melown.catalog.domain.model.condition.UsedCondition;
import com.melown.catalog.domain.model.snapshots.ProductSnapshot;
import com.melown.catalog.domain.model.specs.NewProductSpec;
import com.melown.catalog.domain.model.specs.UsedProductSpec;
import com.melown.catalog.domain.model.status.DraftedStatus;
import com.melown.catalog.domain.model.status.ProductStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

public class Product {

  private final ProductId id;
  private final String name;
  private final String description;
  private final Specifications specifications;
  private final UUID vendorId;
  private ProductCondition productCondition;
  private ProductStatus productStatus;
  private final Long version;
  private final Instant createdAt;
  private final Instant updatedAt;

  private Product(
      ProductId id,
      String name,
      String description,
      Specifications specifications,
      UUID vendorId,
      ProductCondition productCondition,
      ProductStatus productStatus,
      Long version,
      Instant createdAt,
      Instant updatedAt) {
    this.id = id;
    this.name = name;
    this.description = description;
    this.specifications = specifications;
    this.vendorId = vendorId;
    this.productCondition = productCondition;
    this.productStatus = productStatus;
    this.version = version;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  // producto publicado en condición "nuevo"
  public static Product createNew(NewProductSpec spec) {

    ProductId productId = ProductId.create();
    Instant expiredDraftDate = Instant.now().plus(2, ChronoUnit.DAYS);

    ProductCondition productCondition = new NewCondition(spec.guarantee());

    ProductStatus productStatus = new DraftedStatus(expiredDraftDate);

    return new Product(
        productId,
        spec.name(),
        spec.description(),
        spec.specifications(),
        spec.vendorId(),
        productCondition,
        productStatus,
        spec.version(),
        Instant.now(),
        Instant.now());
  }

  // producto publicado en condición "usado"
  public static Product createUsed(UsedProductSpec spec) {

    ProductId productId = ProductId.create();
    Instant expiredDraftDate = Instant.now().plus(2, ChronoUnit.DAYS);

    ProductCondition productCondition =
        new UsedCondition(spec.usedDescription(), spec.conditionGrade());

    ProductStatus productStatus = new DraftedStatus(expiredDraftDate);

    return new Product(
        productId,
        spec.name(),
        spec.description(),
        spec.specifications(),
        spec.vendorId(),
        productCondition,
        productStatus,
        spec.version(),
        Instant.now(),
        Instant.now());
  }

  public static Product rehydrate(ProductSnapshot snapshot) {
    ProductId productId = ProductId.reconstitute(snapshot.id().value());
    return new Product(
        productId,
        snapshot.name(),
        snapshot.description(),
        snapshot.specifications(),
        snapshot.vendorId(),
        snapshot.productCondition(),
        snapshot.productStatus(),
        snapshot.version(),
        snapshot.createdAt(),
        snapshot.updatedAt());
  }

  public ProductSnapshot toSnapshot() {
    return new ProductSnapshot(
        id,
        name,
        description,
        specifications,
        vendorId,
        productCondition,
        productStatus,
        version,
        createdAt,
        updatedAt);
  }
}
