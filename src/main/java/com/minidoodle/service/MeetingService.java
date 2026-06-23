package com.minidoodle.service;

import com.minidoodle.config.CacheConfig;
import com.minidoodle.domain.Meeting;
import com.minidoodle.domain.Slot;
import com.minidoodle.domain.SlotStatus;
import com.minidoodle.domain.User;
import com.minidoodle.dto.request.CreateMeetingRequest;
import com.minidoodle.dto.response.MeetingResponse;
import com.minidoodle.exception.ConflictException;
import com.minidoodle.exception.InvalidSlotException;
import com.minidoodle.exception.ResourceNotFoundException;
import com.minidoodle.mapper.DomainMapper;
import com.minidoodle.repository.MeetingRepository;
import com.minidoodle.repository.SlotRepository;
import com.minidoodle.repository.UserRepository;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final DomainMapper mapper;

    @Timed("minidoodle.meeting.create")
    @CacheEvict(value = CacheConfig.CACHE_AVAILABILITY, allEntries = true)
    @Transactional
    public MeetingResponse bookMeeting(Long slotId, CreateMeetingRequest req) {
        if (req.title() == null || req.title().isBlank()) {
            throw new InvalidSlotException("Meeting title is required.");
        }

        // Pessimistic lock the slot row so concurrent booking attempts on the
        // same slot serialize at the DB. The slot.meeting unique constraint
        // is a safety net.
        Slot slot = slotRepository.findByIdForUpdate(slotId)
                .orElseThrow(() -> ResourceNotFoundException.of("Slot", slotId));

        if (slot.isBooked()) {
            throw new ConflictException("Slot is already booked.");
        }
        if (slot.getStatus() != SlotStatus.FREE) {
            throw new ConflictException("Only FREE slots can be booked. Current status: " + slot.getStatus());
        }

        User organizer = userRepository.findById(req.organizerId())
                .orElseThrow(() -> ResourceNotFoundException.of("Organizer", req.organizerId()));

        Meeting meeting = Meeting.builder()
                .slot(slot)
                .organizer(organizer)
                .title(req.title().trim())
                .description(req.description())
                .build();

        // Bulk-fetch all participants (including organizer auto-add) in one query.
        Set<Long> wantedIds = new HashSet<>(req.participantUserIds() == null ? Set.of() : req.participantUserIds());
        wantedIds.add(organizer.getId()); // organizer is always a participant

        if (!wantedIds.isEmpty()) {
            List<User> participants = userRepository.findAllByIdIn(wantedIds);
            if (participants.size() != wantedIds.size()) {
                Set<Long> foundIds = participants.stream().map(User::getId).collect(Collectors.toSet());
                Set<Long> missing = new HashSet<>(wantedIds);
                missing.removeAll(foundIds);
                throw ResourceNotFoundException.of("Participant users", missing);
            }
            // Deduplicate by id (safe even though input is a Set).
            Map<Long, User> uniq = participants.stream().collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));
            uniq.values().forEach(meeting::addParticipant);
        }

        slot.setStatus(SlotStatus.BUSY);
        slot.setMeeting(meeting);
        Meeting saved = meetingRepository.save(meeting);
        log.info("Created meeting id={} slotId={} organizerId={} participants={}",
                saved.getId(), slot.getId(), organizer.getId(), saved.getParticipants().size());
        return mapper.toMeetingResponse(saved);
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
        Meeting m = meetingRepository.findById(meetingId)
                .orElseThrow(() -> ResourceNotFoundException.of("Meeting", meetingId));
        Slot slot = m.getSlot();
        slot.setStatus(SlotStatus.FREE);
        slot.setMeeting(null);
        meetingRepository.delete(m);
        log.info("Cancelled meeting id={} freed slot id={}", meetingId, slot.getId());
    }
}
