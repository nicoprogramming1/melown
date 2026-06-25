package com.melown.catalog.application.port.in;

import java.util.Map;

public record CreateNewProductCommand(
        String title,
        String description,
        boolean guarantee,
        Map<String, String> specifications
        ) implements CreateProductCommandKind {
}
