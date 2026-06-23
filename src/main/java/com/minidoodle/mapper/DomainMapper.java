package com.minidoodle.mapper;

import com.minidoodle.domain.Meeting;
import com.minidoodle.domain.Slot;
import com.minidoodle.domain.User;
import com.minidoodle.dto.response.MeetingResponse;
import com.minidoodle.dto.response.SlotResponse;
import com.minidoodle.dto.response.UserResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.factory.Mappers;

import java.util.Set;
import java.util.stream.Collectors;

@Mapper(componentModel = "spring")
public interface DomainMapper {

    DomainMapper INSTANCE = Mappers.getMapper(DomainMapper.class);

    @Mapping(target = "timezone", expression = "java(user.getCalendar() != null ? user.getCalendar().getTimezone() : \"UTC\")")
    UserResponse toUserResponse(User user);

    @Mapping(target = "userId",    source = "calendar.user.id")
    @Mapping(target = "meetingId", source = "meeting.id")
    SlotResponse toSlotResponse(Slot slot);

    @Mapping(target = "slotId",       source = "slot.id")
    @Mapping(target = "organizerId",  source = "organizer.id")
    @Mapping(target = "startTime",    source = "slot.startTime")
    @Mapping(target = "endTime",      source = "slot.endTime")
    @Mapping(target = "participants", source = "participants", qualifiedByName = "mapParticipants")
    MeetingResponse toMeetingResponse(Meeting meeting);

    @Named("mapParticipants")
    default Set<MeetingResponse.ParticipantResponse> mapParticipants(
            Set<com.minidoodle.domain.MeetingParticipant> ps) {
        if (ps == null) return Set.of();
        return ps.stream()
                .map(p -> new MeetingResponse.ParticipantResponse(p.getUser().getId(), p.getResponseStatus().name()))
                .collect(Collectors.toSet());
    }
}
