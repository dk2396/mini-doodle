package com.minidoodle.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record AggregateAvailabilityResponse(
        Instant from,
        Instant to,
        Map<Long, List<SlotResponse>> slotsByUser
) {}
