package com.melown.catalog.domain.model;

import java.util.Map;

public record Specifications(
        Map<String, String> values
) {
}
