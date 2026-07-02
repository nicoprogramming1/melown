package com.melown.catalog.common.helpers;

import com.google.protobuf.Timestamp;
import java.time.Instant;

public class TimestampMapper {
  public static Timestamp toTimestamp(Instant instant) {
    return Timestamp.newBuilder()
        .setSeconds(instant.getEpochSecond())
        .setNanos(instant.getNano())
        .build();
  }
}
