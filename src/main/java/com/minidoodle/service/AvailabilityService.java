package com.minidoodle.service;

import com.minidoodle.config.AppProperties;
import com.minidoodle.config.CacheConfig;
import com.minidoodle.domain.Calendar;
import com.minidoodle.domain.Slot;
import com.minidoodle.domain.SlotStatus;
import com.minidoodle.dto.request.AggregateAvailabilityRequest;
import com.minidoodle.dto.response.AggregateAvailabilityResponse;
import com.minidoodle.dto.response.SlotResponse;
import com.minidoodle.exception.InvalidSlotException;
import com.minidoodle.exception.ResourceNotFoundException;
import com.minidoodle.mapper.DomainMapper;
import com.minidoodle.repository.CalendarRepository;
import com.minidoodle.repository.SlotRepository;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AvailabilityService {

    private final SlotRepository slotRepository;
    private final CalendarRepository calendarRepository;
    private final DomainMapper mapper;
    private final AppProperties props;

    /**
     * Aggregates availability for several users in a time window.
     *
     * Cached in Redis - the cost is one DB hit + grouping in Java. The key
     * incorporates user ids (sorted), window and status so it can be reused
     * across requests that share these dimensions. TTL is short to keep
     * staleness bounded.
     */
    @Timed("minidoodle.availability.aggregate")
    // Records auto-generate a unique, stable toString() that includes every field.
    // Using it as the cache key so different requests can never collide.
    @Cacheable(value = CacheConfig.CACHE_AVAILABILITY, key = "#req.toString()")
    @Transactional(readOnly = true)
    public AggregateAvailabilityResponse aggregate(AggregateAvailabilityRequest req) {
        if (req.from() == null || req.to() == null || !req.from().isBefore(req.to())) {
            throw new InvalidSlotException("'from' must be strictly before 'to'.");
        }
        if (Duration.between(req.from(), req.to()).toDays() > props.slots().maxFutureDays()) {
            throw new InvalidSlotException("Range exceeds maximum window of " + props.slots().maxFutureDays() + " days.");
        }
        if (req.userIds().size() > 50) {
            throw new InvalidSlotException("Too many users in one request (max 50).");
        }

        // Resolve user -> calendar in one query batch.
        List<Calendar> calendars = req.userIds().stream()
                .map(uid -> calendarRepository.findByUserId(uid)
                        .orElseThrow(() -> ResourceNotFoundException.of("Calendar for user", uid)))
                .toList();

        Map<Long, Long> calendarIdToUserId = calendars.stream()
                .collect(Collectors.toMap(Calendar::getId, c -> c.getUser().getId()));

        SlotStatus filter = req.status() != null ? req.status() : SlotStatus.FREE;
        List<Slot> all = slotRepository.findInRangeForCalendars(
                calendarIdToUserId.keySet(), req.from(), req.to(), filter);

        // Group results by user id - preserve sorted-by-startTime order from the query.
        Map<Long, List<SlotResponse>> grouped = new HashMap<>();
        for (Long uid : req.userIds()) {
            grouped.put(uid, new java.util.ArrayList<>());
        }
        for (Slot s : all) {
            Long userId = calendarIdToUserId.get(s.getCalendar().getId());
            grouped.get(userId).add(mapper.toSlotResponse(s));
        }

        log.debug("Aggregated availability: users={} window=[{} → {}] hits={}",
                req.userIds().size(), req.from(), req.to(), all.size());
        return new AggregateAvailabilityResponse(req.from(), req.to(), grouped);
    }
}
