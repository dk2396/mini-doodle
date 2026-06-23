package com.minidoodle.dto.response;

import java.time.Instant;
import java.util.Set;

public record MeetingResponse(
        Long id,
        Long slotId,
        Long organizerId,
        String title,
        String description,
        Instant startTime,
        Instant endTime,
        Set<ParticipantResponse> participants,
        Instant createdAt
) {
    public record ParticipantResponse(Long userId, String responseStatus) {}
}
