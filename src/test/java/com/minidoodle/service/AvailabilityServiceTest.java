package com.minidoodle.service;

import com.minidoodle.config.AppProperties;
import com.minidoodle.domain.Calendar;
import com.minidoodle.domain.Slot;
import com.minidoodle.domain.SlotStatus;
import com.minidoodle.domain.User;
import com.minidoodle.dto.request.AggregateAvailabilityRequest;
import com.minidoodle.dto.response.AggregateAvailabilityResponse;
import com.minidoodle.dto.response.SlotResponse;
import com.minidoodle.exception.InvalidSlotException;
import com.minidoodle.exception.ResourceNotFoundException;
import com.minidoodle.mapper.DomainMapper;
import com.minidoodle.repository.CalendarRepository;
import com.minidoodle.repository.SlotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AvailabilityServiceTest {

    @Mock SlotRepository slotRepository;
    @Mock CalendarRepository calendarRepository;
    @Mock DomainMapper mapper;

    AppProperties props;
    AvailabilityService service;

    @BeforeEach
    void setUp() {
        props = new AppProperties(
                new AppProperties.Slots(5, 480, 365, 200),
                new AppProperties.Aggregate(30));
        service = new AvailabilityService(slotRepository, calendarRepository, mapper, props);
    }

    @Test
    void rejectsInvertedRange() {
        Instant now = Instant.now();
        var req = new AggregateAvailabilityRequest(Set.of(1L, 2L), now.plusSeconds(60), now, SlotStatus.FREE);
        assertThatThrownBy(() -> service.aggregate(req))
                .isInstanceOf(InvalidSlotException.class);
    }

    @Test
    void rejectsExcessivelyLongRange() {
        Instant from = Instant.now();
        Instant to = from.plus(400, ChronoUnit.DAYS);
        var req = new AggregateAvailabilityRequest(Set.of(1L), from, to, SlotStatus.FREE);
        assertThatThrownBy(() -> service.aggregate(req))
                .isInstanceOf(InvalidSlotException.class);
    }

    @Test
    void rejectsWhenUserHasNoCalendar() {
        when(calendarRepository.findByUserId(1L)).thenReturn(Optional.empty());
        var req = new AggregateAvailabilityRequest(Set.of(1L),
                Instant.now(), Instant.now().plus(1, ChronoUnit.HOURS), SlotStatus.FREE);
        assertThatThrownBy(() -> service.aggregate(req))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void groupsResultsByUserAndPreservesEmptyUsers() {
        Instant from = Instant.now();
        Instant to = from.plus(1, ChronoUnit.DAYS);

        User u1 = User.builder().id(1L).email("a@x.com").displayName("A").build();
        User u2 = User.builder().id(2L).email("b@x.com").displayName("B").build();
        Calendar c1 = Calendar.builder().id(10L).user(u1).timezone("UTC").build();
        Calendar c2 = Calendar.builder().id(20L).user(u2).timezone("UTC").build();

        when(calendarRepository.findByUserId(1L)).thenReturn(Optional.of(c1));
        when(calendarRepository.findByUserId(2L)).thenReturn(Optional.of(c2));

        Slot s = Slot.builder().id(100L).calendar(c1)
                .startTime(from.plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES))
                .endTime(from.plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES))
                .status(SlotStatus.FREE).build();
        when(slotRepository.findInRangeForCalendars(any(), any(), any(), any()))
                .thenReturn(List.of(s));
        when(mapper.toSlotResponse(any())).thenAnswer(inv -> {
            Slot sl = inv.getArgument(0);
            return new SlotResponse(sl.getId(), 1L, sl.getStartTime(), sl.getEndTime(), sl.getStatus(),
                    null, sl.getVersion(), sl.getCreatedAt(), sl.getUpdatedAt());
        });

        AggregateAvailabilityResponse resp = service.aggregate(
                new AggregateAvailabilityRequest(Set.of(1L, 2L), from, to, SlotStatus.FREE));

        assertThat(resp.slotsByUser()).containsKeys(1L, 2L);
        assertThat(resp.slotsByUser().get(1L)).hasSize(1);
        assertThat(resp.slotsByUser().get(2L)).isEmpty();
    }
}
