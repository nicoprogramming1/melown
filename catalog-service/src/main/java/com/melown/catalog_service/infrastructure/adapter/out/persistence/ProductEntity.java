package com.melown.catalog_service.infrastructure.adapter.out.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity()
@Table(name = "product")
public class ProductEntity {
    
    @Id()
    UUID id;
    String name;
    String description;
    
}
