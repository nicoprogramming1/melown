package com.melown.catalog.infrastructure.adapter.out.dto;

import com.melown.catalog.domain.model.ProductId;
import com.melown.catalog.domain.model.Specifications;
import com.melown.catalog.domain.model.condition.ProductCondition;
import com.melown.catalog.domain.model.status.ProductStatus;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class GetProductResult {
  ProductId id;
  String title;
  String description;
  Specifications specifications;
  ProductId vendorId;
  ProductCondition productCondition;
  ProductStatus productStatus;
  Instant createdAt;
}
