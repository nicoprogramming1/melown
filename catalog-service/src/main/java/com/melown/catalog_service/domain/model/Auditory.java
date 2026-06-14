package com.melown.catalog_service.domain.model;

import java.util.Date;

public abstract class Auditory {

    private final boolean isActive;
    private final Date createdAt;
    private final Date updatedAt;

    Auditory(
            boolean isActive,
            Date createdAt,
            Date updatedAt

    ) {
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.isActive = isActive;
    }
}
