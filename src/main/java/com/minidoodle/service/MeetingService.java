package com.minidoodle.service;

import com.minidoodle.config.CacheConfig;
import com.minidoodle.domain.Calendar;
import com.minidoodle.domain.Meeting;
import com.minidoodle.domain.MeetingParticipant;
import com.minidoodle.domain.Slot;
import com.minidoodle.domain.SlotStatus;
import com.minidoodle.domain.User;
import com.minidoodle.dto.request.CreateMeetingRequest;
import com.minidoodle.dto.response.MeetingResponse;
import com.minidoodle.exception.ConflictException;
import com.minidoodle.exception.InvalidSlotException;
import com.minidoodle.exception.ResourceNotFoundException;
import com.minidoodle.mapper.DomainMapper;
import com.minidoodle.repository.CalendarRepository;
import com.minidoodle.repository.MeetingRepository;
import com.minidoodle.repository.SlotRepository;
import com.minidoodle.repository.UserRepository;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;


@Slf4j
@Service
@RequiredArgsConstructor
public class MeetingService {

    private final MeetingRepository meetingRepository;
    private final SlotRepository slotRepository;
    private final UserRepository userRepository;
    private final CalendarRepository calendarRepository;
    private final DomainMapper mapper;

    @Timed("minidoodle.meeting.create")
    @CacheEvict(value = CacheConfig.CACHE_AVAILABILITY, allEntries = true)
    @Transactional
    public MeetingResponse bookMeeting(Long slotId, CreateMeetingRequest req) {
        if (req.title() == null || req.title().isBlank()) {
            throw new InvalidSlotException("Meeting title is required.");
        }

        // --- 1. Lock organizer slot, validate ----------------------------
        Slot organizerSlot = slotRepository.findByIdForUpdate(slotId)
                .orElseThrow(() -> ResourceNotFoundException.of("Slot", slotId));

        if (organizerSlot.isBooked()) {
            throw new ConflictException("Slot is already booked.");
        }
        if (organizerSlot.getStatus() != SlotStatus.FREE) {
            throw new ConflictException("Only FREE slots can be booked. Current status: " + organizerSlot.getStatus());
        }

        User organizer = userRepository.findById(req.organizerId())
                .orElseThrow(() -> ResourceNotFoundException.of("Organizer", req.organizerId()));

        // Confirm the slot really belongs to the organizer's calendar - a
        // booking on someone else's calendar is a malformed request.
        if (!organizerSlot.getCalendar().getUser().getId().equals(organizer.getId())) {
            throw new ConflictException("Slot " + slotId + " does not belong to organizer " + organizer.getId());
        }

        // --- 2. Resolve attendees (organizer + invitees, deduped) --------
        Set<Long> attendeeIds = new HashSet<>(req.participantUserIds() == null ? Set.of() : req.participantUserIds());
        attendeeIds.add(organizer.getId());

        List<User> attendees = userRepository.findAllByIdIn(attendeeIds);
        if (attendees.size() != attendeeIds.size()) {
            Set<Long> found = attendees.stream().map(User::getId).collect(Collectors.toSet());
            Set<Long> missing = new HashSet<>(attendeeIds);
            missing.removeAll(found);
            throw ResourceNotFoundException.of("Participant users", missing);
        }
        Map<Long, User> attendeeById = attendees.stream()
                .collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));

        // --- 3. Persist meeting shell first so slots can reference its id --
        Meeting meeting = Meeting.builder()
                .organizer(organizer)
                .title(req.title().trim())
                .description(req.description())
                .build();
        meetingRepository.save(meeting);   // populates id in place; same instance

        Instant start = organizerSlot.getStartTime();
        Instant end   = organizerSlot.getEndTime();

        // --- 4. For each attendee, bind a slot to the meeting ------------
        // Lock order: sort by user id to avoid deadlocks under concurrency
        // when multiple bookings touch overlapping attendee sets.
        List<Long> sortedIds = new ArrayList<>(attendeeIds);
        sortedIds.sort(Long::compareTo);

        for (Long uid : sortedIds) {
            User attendee = attendeeById.get(uid);
            Slot slot;

            if (uid.equals(organizer.getId())) {
                // The organizer's slot already exists and is locked.
                slot = organizerSlot;
            } else {
                Calendar cal = calendarRepository.findByUserId(uid)
                        .orElseThrow(() -> ResourceNotFoundException.of("Calendar for user", uid));


                slot = slotRepository.findExactFreeMatch(cal.getId(), start, end)
                        .orElseThrow(() -> new ConflictException(
                                "User " + uid + " has not advertised availability for "
                                        + start + " to " + end + ". They must create a FREE slot "
                                        + "at this time before being invited."));
            }

            slot.setStatus(SlotStatus.BUSY);
            meeting.addSlot(slot);   // sets slot.meeting and adds to meeting.slots

            MeetingParticipant.ResponseStatus respStatus = uid.equals(organizer.getId())
                    ? MeetingParticipant.ResponseStatus.ACCEPTED
                    : MeetingParticipant.ResponseStatus.PENDING;
            meeting.addParticipant(attendee, respStatus);
        }

        log.info("Booked meeting id={} organizerId={} attendees={}",
                meeting.getId(), organizer.getId(), meeting.getSlots().size());
        return mapper.toMeetingResponse(meeting);
    }

    @Transactional(readOnly = true)
    public MeetingResponse getMeeting(Long meetingId) {
        Meeting m = meetingRepository.findByIdWithDetails(meetingId)
                .orElseThrow(() -> ResourceNotFoundException.of("Meeting", meetingId));
        return mapper.toMeetingResponse(m);
    }

    @Timed("minidoodle.meeting.cancel")
    @CacheEvict(value = CacheConfig.CACHE_AVAILABILITY, allEntries = true)
    @Transactional
    public void cancelMeeting(Long meetingId) {
        Meeting m = meetingRepository.findByIdWithDetails(meetingId)
                .orElseThrow(() -> ResourceNotFoundException.of("Meeting", meetingId));


        List<Slot> slots = new ArrayList<>(m.getSlots());
        for (Slot slot : slots) {
            slot.setMeeting(null);
            slot.setStatus(SlotStatus.FREE);
        }
        meetingRepository.delete(m);
        log.info("Cancelled meeting id={} freed slots={}", meetingId, slots.size());
    }
}