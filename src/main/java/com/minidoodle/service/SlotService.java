package com.minidoodle.service;

import com.minidoodle.config.AppProperties;
import com.minidoodle.config.CacheConfig;
import com.minidoodle.domain.Calendar;
import com.minidoodle.domain.Slot;
import com.minidoodle.domain.SlotStatus;
import com.minidoodle.dto.request.BulkCreateSlotsRequest;
import com.minidoodle.dto.request.CreateSlotRequest;
import com.minidoodle.dto.request.UpdateSlotRequest;
import com.minidoodle.dto.response.SlotResponse;
import com.minidoodle.exception.ConflictException;
import com.minidoodle.exception.InvalidSlotException;
import com.minidoodle.exception.ResourceNotFoundException;
import com.minidoodle.exception.SlotOverlapException;
import com.minidoodle.mapper.DomainMapper;
import com.minidoodle.repository.CalendarRepository;
import com.minidoodle.repository.SlotRepository;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SlotService {

    private final SlotRepository slotRepository;
    private final CalendarRepository calendarRepository;
    private final DomainMapper mapper;
    private final AppProperties props;

    // ----- Create -----

    @Timed("minidoodle.slot.create")
    @CacheEvict(value = CacheConfig.CACHE_AVAILABILITY, allEntries = true)
    @Transactional
    public SlotResponse createSlot(Long userId, CreateSlotRequest req) {
        validateTimeRange(req.startTime(), req.endTime());

        Calendar calendar = calendarRepository.findByUserId(userId)
                .orElseThrow(() -> ResourceNotFoundException.of("Calendar for user", userId));

        // Pre-check is best-effort: between the SELECT and INSERT another
        // concurrent transaction could insert an overlapping slot. The DB
        // EXCLUDE constraint is what actually guarantees safety and will
        // surface as DataIntegrityViolationException -> 409 to the caller.
        if (slotRepository.existsOverlapping(calendar.getId(), req.startTime(), req.endTime(), null)) {
            throw new SlotOverlapException("Slot overlaps an existing slot in the calendar.");
        }

        SlotStatus status = req.status() != null ? req.status() : SlotStatus.FREE;
        Slot slot = Slot.builder()
                .calendar(calendar)
                .startTime(req.startTime())
                .endTime(req.endTime())
                .status(status)
                .build();
        Slot saved = slotRepository.save(slot);
        log.info("Created slot id={} userId={} start={} end={}", saved.getId(), userId, saved.getStartTime(), saved.getEndTime());
        return mapper.toSlotResponse(saved);
    }

    @Timed("minidoodle.slot.bulk_create")
    @CacheEvict(value = CacheConfig.CACHE_AVAILABILITY, allEntries = true)
    @Transactional
    public List<SlotResponse> createSlotsBulk(Long userId, BulkCreateSlotsRequest req) {
        Calendar calendar = calendarRepository.findByUserId(userId)
                .orElseThrow(() -> ResourceNotFoundException.of("Calendar for user", userId));

        // Validate the batch first: lengths, future window, and self-overlap.
        for (CreateSlotRequest cr : req.slots()) {
            validateTimeRange(cr.startTime(), cr.endTime());
        }
        ensureBatchNonOverlapping(req.slots());

        List<Slot> entities = req.slots().stream()
                .map(cr -> Slot.builder()
                        .calendar(calendar)
                        .startTime(cr.startTime())
                        .endTime(cr.endTime())
                        .status(cr.status() != null ? cr.status() : SlotStatus.FREE)
                        .build())
                .toList();

        // saveAll lets Hibernate use the batch_size=50 setting. The EXCLUDE
        // constraint still enforces correctness against existing rows.
        List<Slot> saved = slotRepository.saveAll(entities);
        return saved.stream().map(mapper::toSlotResponse).toList();
    }

    // ----- Read -----

    @Timed("minidoodle.slot.list")
    @Transactional(readOnly = true)
    public Page<SlotResponse> listSlots(Long userId, Instant from, Instant to, SlotStatus status, Pageable pageable) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw new InvalidSlotException("'from' must be before 'to' and both must be provided.");
        }
        if (Duration.between(from, to).toDays() > props.slots().maxFutureDays()) {
            throw new InvalidSlotException("Requested range exceeds maximum allowed window.");
        }
        if (pageable.getPageSize() > props.slots().maxPageSize()) {
            throw new InvalidSlotException("Page size exceeds maximum: " + props.slots().maxPageSize());
        }
        Calendar calendar = calendarRepository.findByUserId(userId)
                .orElseThrow(() -> ResourceNotFoundException.of("Calendar for user", userId));
        return slotRepository.findInRange(calendar.getId(), from, to, status, pageable)
                .map(mapper::toSlotResponse);
    }

    @Transactional(readOnly = true)
    public SlotResponse getSlot(Long slotId) {
        return mapper.toSlotResponse(loadSlot(slotId));
    }

    // ----- Update -----

    @Timed("minidoodle.slot.update")
    @CacheEvict(value = CacheConfig.CACHE_AVAILABILITY, allEntries = true)
    @Transactional
    public SlotResponse updateSlot(Long slotId, UpdateSlotRequest req) {
        // Pessimistic lock guarantees no concurrent state transitions on this row.
        Slot slot = slotRepository.findByIdForUpdate(slotId)
                .orElseThrow(() -> ResourceNotFoundException.of("Slot", slotId));

        if (slot.isBooked()) {
            throw new ConflictException("Slot is already booked as a meeting and cannot be modified. " +
                    "Delete the meeting first.");
        }

        Instant newStart = req.startTime() != null ? req.startTime() : slot.getStartTime();
        Instant newEnd   = req.endTime()   != null ? req.endTime()   : slot.getEndTime();
        SlotStatus newStatus = req.status() != null ? req.status() : slot.getStatus();

        boolean timeChanged = !newStart.equals(slot.getStartTime()) || !newEnd.equals(slot.getEndTime());
        if (timeChanged) {
            validateTimeRange(newStart, newEnd);
            if (slotRepository.existsOverlapping(slot.getCalendar().getId(), newStart, newEnd, slot.getId())) {
                throw new SlotOverlapException("Updated slot would overlap an existing slot.");
            }
            slot.setStartTime(newStart);
            slot.setEndTime(newEnd);
        }
        slot.setStatus(newStatus);

        return mapper.toSlotResponse(slot);
    }

    // ----- Delete -----

    @Timed("minidoodle.slot.delete")
    @CacheEvict(value = CacheConfig.CACHE_AVAILABILITY, allEntries = true)
    @Transactional
    public void deleteSlot(Long slotId) {
        Slot slot = loadSlot(slotId);
        if (slot.isBooked()) {
            throw new ConflictException("Slot is bound to a meeting. Delete the meeting first.");
        }
        slotRepository.delete(slot);
        log.info("Deleted slot id={}", slotId);
    }

    // ----- Helpers -----

    private Slot loadSlot(Long slotId) {
        return slotRepository.findById(slotId)
                .orElseThrow(() -> ResourceNotFoundException.of("Slot", slotId));
    }

    private void validateTimeRange(Instant start, Instant end) {
        if (start == null || end == null) {
            throw new InvalidSlotException("startTime and endTime are required.");
        }
        if (!start.isBefore(end)) {
            throw new InvalidSlotException("startTime must be strictly before endTime.");
        }
        // Slots must be on minute boundaries to keep things tidy.
        if (start.truncatedTo(ChronoUnit.MINUTES).compareTo(start) != 0
                || end.truncatedTo(ChronoUnit.MINUTES).compareTo(end) != 0) {
            throw new InvalidSlotException("Slot times must be on minute boundaries (no seconds/millis).");
        }
        Duration d = Duration.between(start, end);
        if (d.toMinutes() < props.slots().minDurationMinutes()) {
            throw new InvalidSlotException("Slot too short. Minimum: " + props.slots().minDurationMinutes() + " minutes.");
        }
        if (d.toMinutes() > props.slots().maxDurationMinutes()) {
            throw new InvalidSlotException("Slot too long. Maximum: " + props.slots().maxDurationMinutes() + " minutes.");
        }
        Instant maxFuture = Instant.now().plus(props.slots().maxFutureDays(), ChronoUnit.DAYS);
        if (end.isAfter(maxFuture)) {
            throw new InvalidSlotException("Slot is too far in the future.");
        }
    }

    private void ensureBatchNonOverlapping(List<CreateSlotRequest> batch) {
        // O(n log n) check that the batch is internally consistent before the DB rejects it.
        List<CreateSlotRequest> sorted = batch.stream()
                .sorted((a, b) -> a.startTime().compareTo(b.startTime()))
                .toList();
        for (int i = 1; i < sorted.size(); i++) {
            if (!sorted.get(i).startTime().isAfter(sorted.get(i - 1).endTime().minusNanos(1))
                && sorted.get(i).startTime().isBefore(sorted.get(i - 1).endTime())) {
                throw new SlotOverlapException("Batch contains overlapping slots.");
            }
        }
    }
}
