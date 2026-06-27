package com.minidoodle.dto.response;

import java.time.Instant;
import java.util.List;

public record MeetingResponse(
        Long id,
        Long organizerId,
        String title,
        String description,
        Instant startTime,
        Instant endTime,
        List<AttendeeResponse> attendees,
        Instant createdAt
) {

    public record AttendeeResponse(
            Long userId,
            Long slotId,
            String responseStatus
    ) {}
}
