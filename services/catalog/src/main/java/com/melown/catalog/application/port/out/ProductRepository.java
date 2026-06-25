package com.melown.catalog.application.port.out;

import com.melown.catalog.domain.model.Product;
import com.melown.catalog.domain.model.ProductId;
import com.melown.catalog.domain.model.snapshots.ProductSnapshot;

import java.util.UUID;

public interface ProductRepository {

    ProductId save(ProductSnapshot snapshot);

    Product retrieve(ProductId publicId);
}
