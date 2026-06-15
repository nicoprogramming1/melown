package com.melown.catalog.application.port.out;

import com.melown.catalog.domain.model.snapshots.ProductSnapshot;
import java.util.UUID;

public interface ProductRepository {

  UUID save(ProductSnapshot snapshot);
}
