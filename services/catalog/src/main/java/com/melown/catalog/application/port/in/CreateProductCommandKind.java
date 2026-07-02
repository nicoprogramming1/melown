package com.melown.catalog.application.port.in;

public sealed interface CreateProductCommandKind
    permits CreateNewProductCommand, CreateUsedProductCommand {}
