package com.minidoodle.mapper;

import com.minidoodle.domain.Meeting;
import com.minidoodle.domain.MeetingParticipant;
import com.minidoodle.domain.Slot;
import com.minidoodle.domain.User;
import com.minidoodle.dto.response.MeetingResponse;
import com.minidoodle.dto.response.SlotResponse;
import com.minidoodle.dto.response.UserResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Mapper(componentModel = "spring")
public interface DomainMapper {

    DomainMapper INSTANCE = Mappers.getMapper(DomainMapper.class);

    @Mapping(target = "timezone", expression = "java(user.getCalendar() != null ? user.getCalendar().getTimezone() : \"UTC\")")
    UserResponse toUserResponse(User user);

    @Mapping(target = "userId",    source = "calendar.user.id")
    @Mapping(target = "meetingId", source = "meeting.id")
    SlotResponse toSlotResponse(Slot slot);


    default MeetingResponse toMeetingResponse(Meeting meeting) {
        if (meeting == null) return null;

        // All slots share the same time range; pick any deterministically.
        Slot anySlot = meeting.getSlots().stream()
                .min(Comparator.comparing(Slot::getId))
                .orElse(null);
        Instant start = anySlot != null ? anySlot.getStartTime() : null;
        Instant end   = anySlot != null ? anySlot.getEndTime()   : null;

        Long organizerId = meeting.getOrganizer().getId();

        // user_id -> slot_id, so we can pair participants with their slot.
        Map<Long, Long> slotByUser = meeting.getSlots().stream()
                .collect(Collectors.toMap(
                        s -> s.getCalendar().getUser().getId(),
                        Slot::getId,
                        (a, b) -> a));

        List<MeetingResponse.AttendeeResponse> attendees = meeting.getParticipants().stream()
                .sorted(Comparator
                        .comparing((MeetingParticipant p) -> !p.getUser().getId().equals(organizerId))
                        .thenComparing(p -> p.getUser().getId()))
                .map(p -> new MeetingResponse.AttendeeResponse(
                        p.getUser().getId(),
                        slotByUser.get(p.getUser().getId()),
                        p.getResponseStatus().name()))
                .toList();

        return new MeetingResponse(
                meeting.getId(),
                organizerId,
                meeting.getTitle(),
                meeting.getDescription(),
                start,
                end,
                attendees,
                meeting.getCreatedAt()
        );
    }

    // Unused but kept for explicit MapStruct registration of the participant type
    @Named("noOp")
    default MeetingParticipant noOp(MeetingParticipant p) { return p; }
}
