package com.melown.catalog.infrastructure.adapter.out.persistence.mappers;

import static com.melown.catalog.domain.model.enums.ConditionKind.NEW;
import static com.melown.catalog.domain.model.enums.ConditionKind.USED;
import static com.melown.catalog.domain.model.enums.ProductStatusKind.*;

import com.melown.catalog.domain.model.Auditory;
import com.melown.catalog.domain.model.Product;
import com.melown.catalog.domain.model.ProductId;
import com.melown.catalog.domain.model.Specifications;
import com.melown.catalog.domain.model.condition.NewCondition;
import com.melown.catalog.domain.model.condition.ProductCondition;
import com.melown.catalog.domain.model.condition.UsedCondition;
import com.melown.catalog.domain.model.snapshots.ProductSnapshot;
import com.melown.catalog.domain.model.status.*;
import com.melown.catalog.infrastructure.adapter.out.dto.CreateProductResult;
import com.melown.catalog.infrastructure.adapter.out.dto.GetProductResult;
import com.melown.catalog.infrastructure.adapter.out.persistence.entities.ProductEntity;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class ProductMapper {

  public static Product toDomain(ProductEntity productEntity) {

    Specifications specifications =
        Specifications.create(
            Optional.ofNullable(productEntity.getSpecifications()).orElse(Map.of()));

    ProductCondition productCondition =
        switch (productEntity.getConditionKind()) {
          case NEW -> new NewCondition(productEntity.isGuarantee());
          case USED ->
              new UsedCondition(
                  productEntity.getUsedDescription(), productEntity.getUsedConditionGrade());
        };

    ProductStatus productStatus =
        switch (productEntity.getStatusKind()) {
          case DRAFTED -> new DraftedStatus(productEntity.getExpirationAt());
          case PUBLISHED -> new PublishedStatus(productEntity.getPublishedAt());
          case PAUSED -> new PausedStatus(productEntity.getPausedBy());
          case ARCHIVED ->
              new ArchivedStatus(productEntity.getArchivedAt(), productEntity.getReason());
        };

    ProductId productId = ProductId.reconstitute(productEntity.getPublicId());
    ProductId vendorId = ProductId.reconstitute(productEntity.getVendorId());

    Auditory auditory =
        new Auditory(
            productEntity.getVersion(), productEntity.getCreatedAt(), productEntity.getUpdatedAt());

    ProductSnapshot snapshot =
        new ProductSnapshot(
            productId,
            productEntity.getTitle(),
            productEntity.getDescription(),
            specifications, // aun tengo las Specifications incompletas
            vendorId,
            productCondition,
            productStatus,
            auditory);

    return Product.rehydrate(snapshot);
  }

  public static ProductEntity toEntity(ProductSnapshot snapshot) {

    ProductEntity productEntity = new ProductEntity();

    productEntity.setPublicId(snapshot.id().value());
    productEntity.setTitle(snapshot.title());
    productEntity.setDescription(snapshot.description());
    productEntity.setSpecifications(snapshot.specifications().values());
    productEntity.setVendorId(snapshot.vendorId().value());
    productEntity.setVersion(snapshot.auditory().version());
    productEntity.setCreatedAt(snapshot.auditory().createdAt());
    productEntity.setUpdatedAt(snapshot.auditory().updatedAt());

    switch (snapshot.productCondition()) {
      case NewCondition n -> {
        productEntity.setGuarantee(n.guarantee());
        productEntity.setConditionKind(NEW);
      }
      case UsedCondition n -> {
        productEntity.setUsedConditionGrade(n.usedConditionGrade());
        productEntity.setUsedDescription(n.usedDescription());
        productEntity.setConditionKind(USED);
      }
    }

    switch (snapshot.productStatus()) {
      case DraftedStatus n -> {
        productEntity.setExpirationAt(n.expirationAt());
        productEntity.setStatusKind(DRAFTED);
      }
      case PublishedStatus n -> {
        productEntity.setPublishedAt(n.publishedAt());
        productEntity.setStatusKind(PUBLISHED);
      }
      case PausedStatus n -> {
        productEntity.setPausedBy(n.pausedBy());
        productEntity.setStatusKind(PAUSED);
      }
      case ArchivedStatus n -> {
        productEntity.setArchivedAt(n.archivedAt());
        productEntity.setReason(n.reason());
        productEntity.setStatusKind(ARCHIVED);
      }
    }

    return productEntity;
  }

  public static CreateProductResult toResultCreate(ProductId id) {
    return new CreateProductResult(id);
  }

  public static GetProductResult toResultGet(ProductSnapshot snapshot) {
    return new GetProductResult(
        snapshot.id(),
        snapshot.title(),
        snapshot.description(),
        snapshot.specifications(),
        snapshot.vendorId(),
        snapshot.productCondition(),
        snapshot.productStatus(),
        snapshot.auditory().createdAt());
  }

  public static ProductId toProductId(UUID id) {
    return ProductId.reconstitute(id);
  }
}
