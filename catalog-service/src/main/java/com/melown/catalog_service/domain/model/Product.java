package com.melown.catalog_service.domain.model;

import java.util.Date;
import java.util.UUID;

public class Product extends Auditory {

    private final ProductId id;
    private final String name;
    private final String description;
    private final Specifications specifications;
    private final UUID ownerId;

    private Product(
            ProductId id,
            String name,
            String description,
            Specifications specifications,
            UUID ownerId,
            Date createdAt,
            Date updatedAt,
            boolean isActive
    ) {
        super(isActive, createdAt, updatedAt);

        this.id = id;
        this.name = name;
        this.description = description;
        this.specifications = specifications;
        this.ownerId = ownerId;
    }
}
