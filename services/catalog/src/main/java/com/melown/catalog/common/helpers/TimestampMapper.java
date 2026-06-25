package com.melown.catalog.common.helpers;

import java.time.Instant;

import com.google.protobuf.Timestamp;

public class TimestampMapper {
    public static Timestamp toTimestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }
}
