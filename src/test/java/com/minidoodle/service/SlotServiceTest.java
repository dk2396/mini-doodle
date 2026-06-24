package com.minidoodle.service;

import com.minidoodle.config.AppProperties;
import com.minidoodle.domain.Calendar;
import com.minidoodle.domain.Meeting;
import com.minidoodle.domain.Slot;
import com.minidoodle.domain.SlotStatus;
import com.minidoodle.domain.User;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SlotServiceTest {

    @Mock SlotRepository slotRepository;
    @Mock CalendarRepository calendarRepository;
    @Mock DomainMapper mapper;
    AppProperties props;

    SlotService service;

    User user;
    Calendar calendar;

    @BeforeEach
    void setUp() {
        props = new AppProperties(
                new AppProperties.Slots(5, 480, 365, 200),
                new AppProperties.Aggregate(30));
        service = new SlotService(slotRepository, calendarRepository, mapper, props);

        user = User.builder().id(1L).email("a@b.com").displayName("Alice").build();
        calendar = Calendar.builder().id(10L).user(user).timezone("UTC").build();
        when(calendarRepository.findByUserId(1L)).thenReturn(Optional.of(calendar));
        when(mapper.toSlotResponse(any(Slot.class))).thenAnswer(inv -> {
            Slot s = inv.getArgument(0);
            return new SlotResponse(s.getId(), 1L, s.getStartTime(), s.getEndTime(), s.getStatus(),
                    s.getMeeting() != null ? s.getMeeting().getId() : null,
                    s.getVersion(), s.getCreatedAt(), s.getUpdatedAt());
        });
    }

    private Instant minute(Instant base, int minutes) {
        return base.truncatedTo(ChronoUnit.MINUTES).plus(minutes, ChronoUnit.MINUTES);
    }

    @Nested
    @DisplayName("createSlot")
    class CreateSlot {

        @Test
        void createsValidFreeSlot() {
            Instant start = minute(Instant.now(), 60);
            Instant end   = minute(start, 30);
            when(slotRepository.existsOverlapping(eq(10L), eq(start), eq(end), eq(null))).thenReturn(false);
            when(slotRepository.save(any(Slot.class))).thenAnswer(inv -> {
                Slot s = inv.getArgument(0);
                s.setId(100L);
                return s;
            });

            SlotResponse resp = service.createSlot(1L, new CreateSlotRequest(start, end, null));

            assertThat(resp.id()).isEqualTo(100L);
            assertThat(resp.status()).isEqualTo(SlotStatus.FREE);
            ArgumentCaptor<Slot> captor = ArgumentCaptor.forClass(Slot.class);
            verify(slotRepository).save(captor.capture());
            assertThat(captor.getValue().getCalendar().getId()).isEqualTo(10L);
        }

        @Test
        void rejectsWhenEndBeforeStart() {
            Instant start = minute(Instant.now(), 60);
            Instant end   = minute(start, -1);
            assertThatThrownBy(() -> service.createSlot(1L, new CreateSlotRequest(start, end, null)))
                    .isInstanceOf(InvalidSlotException.class)
                    .hasMessageContaining("strictly before");
        }

        @Test
        void rejectsWhenEndEqualsStart() {
            Instant start = minute(Instant.now(), 60);
            assertThatThrownBy(() -> service.createSlot(1L, new CreateSlotRequest(start, start, null)))
                    .isInstanceOf(InvalidSlotException.class);
        }

        @Test
        void rejectsTooShortSlot() {
            Instant start = minute(Instant.now(), 60);
            Instant end   = start.plus(1, ChronoUnit.MINUTES); // < min 5
            assertThatThrownBy(() -> service.createSlot(1L, new CreateSlotRequest(start, end, null)))
                    .isInstanceOf(InvalidSlotException.class)
                    .hasMessageContaining("too short");
        }

        @Test
        void rejectsTooLongSlot() {
            Instant start = minute(Instant.now(), 60);
            Instant end   = start.plus(9, ChronoUnit.HOURS); // > 480 minutes
            assertThatThrownBy(() -> service.createSlot(1L, new CreateSlotRequest(start, end, null)))
                    .isInstanceOf(InvalidSlotException.class)
                    .hasMessageContaining("too long");
        }

        @Test
        void rejectsNonMinuteAlignedTimes() {
            Instant start = Instant.now().plusSeconds(60).plusNanos(500);
            Instant end   = start.plus(30, ChronoUnit.MINUTES);
            assertThatThrownBy(() -> service.createSlot(1L, new CreateSlotRequest(start, end, null)))
                    .isInstanceOf(InvalidSlotException.class)
                    .hasMessageContaining("minute boundaries");
        }

        @Test
        void rejectsTooFarInFuture() {
            Instant start = minute(Instant.now(), 60).plus(400, ChronoUnit.DAYS);
            Instant end   = start.plus(30, ChronoUnit.MINUTES);
            assertThatThrownBy(() -> service.createSlot(1L, new CreateSlotRequest(start, end, null)))
                    .isInstanceOf(InvalidSlotException.class)
                    .hasMessageContaining("too far");
        }

        @Test
        void rejectsOverlappingSlot() {
            Instant start = minute(Instant.now(), 60);
            Instant end   = minute(start, 30);
            when(slotRepository.existsOverlapping(eq(10L), eq(start), eq(end), eq(null))).thenReturn(true);

            assertThatThrownBy(() -> service.createSlot(1L, new CreateSlotRequest(start, end, null)))
                    .isInstanceOf(SlotOverlapException.class);
            verify(slotRepository, never()).save(any());
        }

        @Test
        void rejectsWhenUserHasNoCalendar() {
            when(calendarRepository.findByUserId(99L)).thenReturn(Optional.empty());
            Instant start = minute(Instant.now(), 60);
            assertThatThrownBy(() -> service.createSlot(99L,
                    new CreateSlotRequest(start, minute(start, 30), null)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("bulk create")
    class BulkCreate {
        @Test
        void rejectsBatchWithInternalOverlap() {
            Instant base = minute(Instant.now(), 60);
            CreateSlotRequest a = new CreateSlotRequest(base, minute(base, 30), null);
            CreateSlotRequest b = new CreateSlotRequest(minute(base, 15), minute(base, 45), null);
            assertThatThrownBy(() -> service.createSlotsBulk(1L,
                    new BulkCreateSlotsRequest(List.of(a, b))))
                    .isInstanceOf(SlotOverlapException.class)
                    .hasMessageContaining("overlapping");
        }

        @Test
        void acceptsAdjacentSlots() {
            Instant base = minute(Instant.now(), 60);
            CreateSlotRequest a = new CreateSlotRequest(base, minute(base, 30), null);
            // adjacent (end == next start) is valid - half-open ranges
            CreateSlotRequest b = new CreateSlotRequest(minute(base, 30), minute(base, 60), null);
            when(slotRepository.saveAll(any())).thenAnswer(inv -> {
                List<Slot> list = inv.getArgument(0);
                long id = 1;
                for (Slot s : list) s.setId(id++);
                return list;
            });

            List<SlotResponse> result = service.createSlotsBulk(1L,
                    new BulkCreateSlotsRequest(List.of(a, b)));
            assertThat(result).hasSize(2);
        }
    }

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        void rejectsUpdateOnBookedSlot() {
            Slot slot = Slot.builder()
                    .id(5L).calendar(calendar)
                    .startTime(minute(Instant.now(), 60))
                    .endTime(minute(Instant.now(), 90))
                    .status(SlotStatus.BUSY)
                    .meeting(Meeting.builder().id(7L).build())
                    .build();
            when(slotRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(slot));

            assertThatThrownBy(() -> service.updateSlot(5L,
                    new UpdateSlotRequest(null, null, SlotStatus.FREE)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("already booked");
        }

        @Test
        void allowsStatusToggleOnFreeSlot() {
            Slot slot = Slot.builder()
                    .id(5L).calendar(calendar)
                    .startTime(minute(Instant.now(), 60))
                    .endTime(minute(Instant.now(), 90))
                    .status(SlotStatus.FREE)
                    .build();
            when(slotRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(slot));

            SlotResponse resp = service.updateSlot(5L,
                    new UpdateSlotRequest(null, null, SlotStatus.BUSY));
            assertThat(resp.status()).isEqualTo(SlotStatus.BUSY);
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {
        @Test
        void rejectsDeleteWhenBooked() {
            Slot slot = Slot.builder()
                    .id(5L).calendar(calendar)
                    .startTime(minute(Instant.now(), 60))
                    .endTime(minute(Instant.now(), 90))
                    .status(SlotStatus.BUSY)
                    .meeting(Meeting.builder().id(7L).build())
                    .build();
            when(slotRepository.findById(5L)).thenReturn(Optional.of(slot));

            assertThatThrownBy(() -> service.deleteSlot(5L))
                    .isInstanceOf(ConflictException.class);
            verify(slotRepository, never()).delete(any());
        }
    }

    @Nested
    @DisplayName("listSlots")
    class List_ {
        @Test
        void rejectsInvertedRange() {
            Instant now = Instant.now();
            assertThatThrownBy(() -> service.listSlots(1L, now.plusSeconds(60), now, null,
                    org.springframework.data.domain.PageRequest.of(0, 50)))
                    .isInstanceOf(InvalidSlotException.class);
        }

        @Test
        void rejectsExcessivePageSize() {
            Instant now = Instant.now();
            assertThatThrownBy(() -> service.listSlots(1L, now, now.plusSeconds(3600), null,
                    org.springframework.data.domain.PageRequest.of(0, 500)))
                    .isInstanceOf(InvalidSlotException.class)
                    .hasMessageContaining("Page size");
        }
    }
}
