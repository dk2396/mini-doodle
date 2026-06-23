package com.minidoodle.dto.response;

import com.minidoodle.domain.SlotStatus;

import java.time.Instant;

public record SlotResponse(
        Long id,
        Long userId,
        Instant startTime,
        Instant endTime,
        SlotStatus status,
        Long meetingId,        // null if not booked
        Long version,
        Instant createdAt,
        Instant updatedAt
) {}
