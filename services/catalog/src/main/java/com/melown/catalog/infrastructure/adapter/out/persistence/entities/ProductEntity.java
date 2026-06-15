package com.melown.catalog.infrastructure.adapter.out.persistence.entities;

import com.melown.catalog.domain.model.enums.ConditionGrade;
import com.melown.catalog.domain.model.enums.ConditionKind;
import com.melown.catalog.domain.model.enums.ProductStatusKind;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity()
@EntityListeners(AuditingEntityListener.class)
@Table(name = "product")
@Getter
@Setter
@NoArgsConstructor
public class ProductEntity {

    @Id()
    UUID id;

    String name;

    String description;

    @JdbcTypeCode(SqlTypes.JSON)              // ← Hibernate 6 lo mapea a JSONB
    @Column(columnDefinition = "jsonb")
    private Map<String, String> specifications = new HashMap<>();

    @Column(name = "vendor_id")
    UUID vendorId;

    // used condition
    @Column(name = "used_description")
    String usedDescription;
    
    @Column(name = "used_condition_grade")
    @Enumerated(EnumType.STRING)
    ConditionGrade usedConditionGrade;

    // new condition
    boolean guarantee;

    // drafted
    @Column(name = "expiration_at")
    Instant expirationAt;

    // published
    @Column(name = "published_at")
    Instant publishedAt;

    // paused
    @Column(name = "paused_by")
    UUID pausedBy;

    // archived
    @Column(name = "archived_at")
    Instant archivedAt;

    String reason;

    @Version
    Long version;

    @Column(name = "status_kind")
    @Enumerated(EnumType.STRING)
    ProductStatusKind statusKind;  //   'DRAFTED' | 'PUBLISHED' | 'PAUSED' | 'ARCHIVED'

    @Column(name = "condition_kind")
    @Enumerated(EnumType.STRING)
    ConditionKind conditionKind;    // 'NEW' | 'USED'

    @CreatedDate
    Instant createdAt;

    @LastModifiedDate
    Instant updatedAt;
}
