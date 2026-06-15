package com.melown.catalog.domain.model.status;

public sealed interface ProductStatus permits DraftedStatus, PublishedStatus, PausedStatus, ArchivedStatus {

    boolean isVisibleInCatalog();

    boolean allowsPurchase();
}
