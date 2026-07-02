package com.melown.catalog.infrastructure.adapter.in.grpc;

import static com.melown.catalog.common.helpers.TimestampMapper.toTimestamp;
import static com.melown.catalog.v1.ConditionGradeProto.*;

import com.google.protobuf.Timestamp;
import com.melown.catalog.application.port.in.CreateNewProductCommand;
import com.melown.catalog.application.port.in.CreateProductCommandKind;
import com.melown.catalog.application.port.in.CreateUsedProductCommand;
import com.melown.catalog.application.port.in.GetProductQuery;
import com.melown.catalog.domain.model.condition.NewCondition;
import com.melown.catalog.domain.model.condition.UsedCondition;
import com.melown.catalog.domain.model.enums.ConditionGrade;
import com.melown.catalog.domain.model.status.*;
import com.melown.catalog.infrastructure.adapter.out.dto.GetProductResult;
import com.melown.catalog.v1.*;

import java.time.Instant;
import java.util.UUID;

public class CatalogGrpcMapper {

    public static CreateProductResponse toResponseCreate(UUID id) {
        return CreateProductResponse.newBuilder().setProductId(id.toString()).build();
    }

    public static CreateProductCommandKind toCommandCreate(CreateProductRequest request) {

        return switch (request.getProductCondition().getKindCase()) {
            case ProductConditionProto.KindCase.USED -> {
                yield new CreateUsedProductCommand(
                        request.getTitle(),
                        request.getDescription(),
                        request.getSpecificationsMap(),
                        request.getProductCondition().getUsed().getUsedDescription(),
                        mapGradeToDomain(request.getProductCondition().getUsed().getGrade()));
            }
            case ProductConditionProto.KindCase.NEW -> {
                yield new CreateNewProductCommand(
                        request.getTitle(),
                        request.getDescription(),
                        request.getProductCondition().getNew().getGuarantee(),
                        request.getSpecificationsMap());
            }
            case ProductConditionProto.KindCase.KIND_NOT_SET -> {
                throw new IllegalArgumentException("");
            }
        };
    }

    public static GetProductQuery toQueryGet(GetProductRequest request) {
        return new GetProductQuery(UUID.fromString(request.getId()));
    }

    public static GetProductResponse toResponseGet(GetProductResult result) {

        SpecificationsProto specifications =
                SpecificationsProto.newBuilder().putAllValues(result.getSpecifications().values()).build();

        ProductConditionProto productCondition =
                switch (result.getProductCondition()) {
                    case NewCondition n -> ProductConditionProto.newBuilder()
                            .setNew(NewConditionProto.newBuilder().setGuarantee(n.guarantee()).build())
                            .build();
                    case UsedCondition u -> ProductConditionProto.newBuilder()
                            .setUsed(
                                    UsedConditionProto.newBuilder()
                                            .setGrade(mapGradeToEntity(u.usedConditionGrade()))
                                            .setUsedDescription(u.usedDescription())
                                            .build())
                            .build();
                };

        ProductStatusProto status =
                switch (result.getProductStatus()) {
                    case DraftedStatus d -> ProductStatusProto.newBuilder()
                            .setDrafted(
                                    DraftedStatusProto.newBuilder()
                                            .setExpirationAt(toTimestamp(d.expirationAt()))
                                            .setIsExpired(result.getIsExpired())
                                            .build())
                            .build();
                    case PublishedStatus p -> ProductStatusProto.newBuilder()
                            .setPublished(
                                    PublishedStatusProto.newBuilder()
                                            .setPublishedAt(toTimestamp(p.publishedAt()))
                                            .build())
                            .build();
                    case PausedStatus p -> ProductStatusProto.newBuilder()
                            .setPaused(
                                    PausedStatusProto.newBuilder().setPausedBy(p.pausedBy().toString()).build())
                            .build();
                    case ArchivedStatus a -> ProductStatusProto.newBuilder()
                            .setArchived(
                                    ArchivedStatusProto.newBuilder()
                                            .setArchivedAt(toTimestamp(a.archivedAt()))
                                            .setReason(a.reason())
                                            .build())
                            .build();
                };

        Instant instant = result.getCreatedAt();

        Timestamp timestamp =
                Timestamp.newBuilder()
                        .setSeconds(instant.getEpochSecond())
                        .setNanos(instant.getNano())
                        .build();

        return GetProductResponse.newBuilder()
                .setId(result.getId().toString())
                .setTitle(result.getTitle())
                .setDescription(result.getDescription())
                .setSpecifications(specifications)
                .setVendorId(result.getVendorId().toString())
                .setProductCondition(productCondition)
                .setProductStatus(status)
                .setCreatedAt(timestamp)
                .build();
    }

    public static ConditionGradeProto mapGradeToEntity(ConditionGrade grade) {
        return switch (grade) {
            case ConditionGrade.PERFECT -> CONDITION_GRADE_PERFECT;
            case ConditionGrade.GOOD -> CONDITION_GRADE_GOOD;
            case ConditionGrade.BAD -> CONDITION_GRADE_BAD;
        };
    }

    public static ConditionGrade mapGradeToDomain(ConditionGradeProto grade) {
        return switch (grade) {
            case CONDITION_GRADE_UNSPECIFIED -> throw new IllegalArgumentException("");
            case CONDITION_GRADE_PERFECT -> ConditionGrade.PERFECT;
            case CONDITION_GRADE_GOOD -> ConditionGrade.GOOD;
            case CONDITION_GRADE_BAD -> ConditionGrade.BAD;
            case UNRECOGNIZED -> throw new IllegalStateException("");
        };
    }
}
