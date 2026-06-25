package com.melown.catalog.domain.model.specs;

import com.melown.catalog.domain.model.Specifications;
import java.util.UUID;

public record NewProductSpec(
    String title,
    String description,
    Specifications specifications,
    UUID vendorId,
    boolean guarantee,
    Long version) {}
