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

public class Product {

    private final ProductId id;
    private final String title;
    private final String description;
    private final Specifications specifications;
    private final ProductId vendorId;
    private final ProductCondition productCondition;
    private final ProductStatus productStatus;
    private final Auditory auditory;

    private Product(
            ProductId id,
            String title,
            String description,
            Specifications specifications,
            ProductId vendorId,
            ProductCondition productCondition,
            ProductStatus productStatus,
            Auditory auditory) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.specifications = specifications;
        this.vendorId = vendorId;
        this.productCondition = productCondition;
        this.productStatus = productStatus;
        this.auditory = auditory;
    }

    // producto creado en condición "nuevo"
    public static Product createNew(NewProductSpec spec, Instant expiredDraftDate, Instant now) {

        ProductId productId = ProductId.create();

        ProductCondition productCondition = new NewCondition(spec.guarantee());
        ProductId vendorId = ProductId.reconstitute(spec.vendorId());
        ProductStatus productStatus = new DraftedStatus(expiredDraftDate);
        Auditory auditory = new Auditory(spec.version(), now, now);

        return new Product(
                productId,
                spec.title(),
                spec.description(),
                spec.specifications(),
                vendorId,
                productCondition,
                productStatus,
                auditory);
    }

    // producto creado en condición "usado"
    public static Product createUsed(UsedProductSpec spec, Instant expiredDraftDate, Instant now) {

        ProductId productId = ProductId.create();

        ProductCondition productCondition =
                new UsedCondition(spec.usedDescription(), spec.conditionGrade());
        ProductId vendorId = ProductId.reconstitute(spec.vendorId());
        ProductStatus productStatus = new DraftedStatus(expiredDraftDate);
        Auditory auditory = new Auditory(spec.version(), now, now);

        return new Product(
                productId,
                spec.title(),
                spec.description(),
                spec.specifications(),
                vendorId,
                productCondition,
                productStatus,
                auditory);
    }

    public static Product rehydrate(ProductSnapshot snapshot) {

        Auditory auditory =
                new Auditory(
                        snapshot.auditory().version(),
                        snapshot.auditory().createdAt(),
                        snapshot.auditory().updatedAt());

        return new Product(
                snapshot.id(),
                snapshot.title(),
                snapshot.description(),
                snapshot.specifications(),
                snapshot.vendorId(),
                snapshot.productCondition(),
                snapshot.productStatus(),
                auditory);
    }

    public ProductSnapshot toSnapshot() {
        return new ProductSnapshot(
                id,
                title,
                description,
                specifications,
                vendorId,
                productCondition,
                productStatus,
                auditory);
    }
}
