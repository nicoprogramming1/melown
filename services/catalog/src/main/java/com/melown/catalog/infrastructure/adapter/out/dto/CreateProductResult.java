package com.melown.catalog.infrastructure.adapter.out.dto;

import com.melown.catalog.domain.model.ProductId;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class CreateProductResult {
    ProductId id;
}
