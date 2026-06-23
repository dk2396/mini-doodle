package com.minidoodle.dto.request;

import com.minidoodle.domain.SlotStatus;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record CreateSlotRequest(
        @NotNull Instant startTime,
        @NotNull Instant endTime,
        SlotStatus status   // optional, defaults to FREE
) {}
