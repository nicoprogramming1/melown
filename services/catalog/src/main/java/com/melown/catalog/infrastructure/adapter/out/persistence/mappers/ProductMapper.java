package com.melown.catalog.infrastructure.adapter.out.persistence.mappers;

import static com.melown.catalog.domain.model.enums.ConditionKind.NEW;
import static com.melown.catalog.domain.model.enums.ConditionKind.USED;
import static com.melown.catalog.domain.model.enums.ProductStatusKind.*;

import com.melown.catalog.domain.model.Product;
import com.melown.catalog.domain.model.ProductId;
import com.melown.catalog.domain.model.Specifications;
import com.melown.catalog.domain.model.condition.NewCondition;
import com.melown.catalog.domain.model.condition.ProductCondition;
import com.melown.catalog.domain.model.condition.UsedCondition;
import com.melown.catalog.domain.model.snapshots.ProductSnapshot;
import com.melown.catalog.domain.model.status.*;
import com.melown.catalog.infrastructure.adapter.out.persistence.entities.ProductEntity;
import java.util.Map;

public class ProductMapper {

  public static Product toDomain(ProductEntity productEntity) {

    Specifications specifications =
        new Specifications(Map.copyOf(productEntity.getSpecifications()));

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

    ProductId productId = ProductId.reconstitute(productEntity.getId());

    ProductSnapshot snapshot =
        new ProductSnapshot(
            productId,
            productEntity.getName(),
            productEntity.getDescription(),
            specifications, // aun tengo las Specifications incompletas
            productEntity.getVendorId(),
            productCondition,
            productStatus,
            productEntity.getVersion(),
            productEntity.getCreatedAt(),
            productEntity.getUpdatedAt());

    return Product.rehydrate(snapshot);
  }

  public static ProductEntity toEntity(ProductSnapshot snapshot) {

    ProductEntity productEntity = new ProductEntity();

    productEntity.setId(snapshot.id().value());
    productEntity.setName(snapshot.name());
    productEntity.setDescription(snapshot.description());
    productEntity.setVendorId(snapshot.vendorId());
    productEntity.setVersion(snapshot.version());
    productEntity.setCreatedAt(snapshot.createdAt());
    productEntity.setUpdatedAt(snapshot.updatedAt());

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
}
