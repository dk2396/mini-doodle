package com.minidoodle.dto.request;

import com.minidoodle.domain.SlotStatus;

import java.time.Instant;

/**
 * Partial update (PATCH-style). All fields optional. The service rejects updates
 * to slots that are already bound to meetings.
 */
public record UpdateSlotRequest(
        Instant startTime,
        Instant endTime,
        SlotStatus status
) {}
