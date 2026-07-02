package com.melown.catalog.common.helpers;

import com.melown.catalog.application.port.in.CreateNewProductCommand;
import com.melown.catalog.application.port.in.CreateProductCommandKind;
import com.melown.catalog.application.port.in.CreateUsedProductCommand;
import com.melown.catalog.domain.model.Product;
import com.melown.catalog.domain.model.Specifications;
import com.melown.catalog.domain.model.specs.NewProductSpec;
import com.melown.catalog.domain.model.specs.UsedProductSpec;

import java.time.Instant;
import java.util.UUID;

public class ProductConstructor {

    public static Product build(
            CreateProductCommandKind createProductCommandKind, Instant expiredDraftDate) {

        UUID vendorId = UUID.fromString("00000000-0000-0000-C000-000000000046");
        Long version = 21L;

        return switch (createProductCommandKind) {
            case CreateNewProductCommand n -> {
                NewProductSpec newProductSpec =
                        new NewProductSpec(
                                n.title(),
                                n.description(),
                                Specifications.create(n.specifications()),
                                vendorId,
                                n.guarantee(),
                                version);

                yield Product.createNew(newProductSpec, expiredDraftDate);
            }
            case CreateUsedProductCommand u -> {
                UsedProductSpec usedProductSpec =
                        new UsedProductSpec(
                                u.title(),
                                u.description(),
                                Specifications.create(u.specifications()),
                                vendorId,
                                u.usedDescription(),
                                u.conditionGrade(),
                                version);

                yield Product.createUsed(usedProductSpec, expiredDraftDate);
            }
        };
    }
}
