package com.melown.catalog.application.port.in;

sealed public interface CreateProductCommandKind permits CreateNewProductCommand, CreateUsedProductCommand {}
