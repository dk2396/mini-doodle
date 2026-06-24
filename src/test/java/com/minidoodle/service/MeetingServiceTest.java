package com.minidoodle.service;

import com.minidoodle.domain.Calendar;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MeetingServiceTest {

    @Mock MeetingRepository meetingRepository;
    @Mock SlotRepository slotRepository;
    @Mock UserRepository userRepository;
    @Mock DomainMapper mapper;

    @InjectMocks MeetingService service;

    User organizer;
    User participant;
    Calendar calendar;
    Slot slot;

    @BeforeEach
    void setUp() {
        organizer = User.builder().id(1L).email("o@x.com").displayName("Org").build();
        participant = User.builder().id(2L).email("p@x.com").displayName("Part").build();
        calendar = Calendar.builder().id(10L).user(organizer).timezone("UTC").build();
        slot = Slot.builder()
                .id(50L).calendar(calendar)
                .startTime(Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES))
                .endTime(Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES))
                .status(SlotStatus.FREE)
                .build();
    }

    @Test
    void booksMeetingOnFreeSlot() {
        when(slotRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(slot));
        when(userRepository.findById(1L)).thenReturn(Optional.of(organizer));
        when(userRepository.findAllByIdIn(any())).thenReturn(List.of(organizer, participant));
        when(meetingRepository.save(any(Meeting.class))).thenAnswer(inv -> {
            Meeting m = inv.getArgument(0);
            m.setId(999L);
            return m;
        });
        when(mapper.toMeetingResponse(any(Meeting.class)))
                .thenAnswer(inv -> new MeetingResponse(999L, 50L, 1L, "Sync", null,
                        slot.getStartTime(), slot.getEndTime(), Set.of(), Instant.now()));

        MeetingResponse resp = service.bookMeeting(50L,
                new CreateMeetingRequest("Sync", null, 1L, Set.of(2L)));

        assertThat(resp.id()).isEqualTo(999L);
        assertThat(slot.getStatus()).isEqualTo(SlotStatus.BUSY);
        assertThat(slot.getMeeting()).isNotNull();
    }

    @Test
    void rejectsBookingAlreadyBookedSlot() {
        slot.setStatus(SlotStatus.BUSY);
        slot.setMeeting(Meeting.builder().id(123L).build());
        when(slotRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(slot));

        assertThatThrownBy(() -> service.bookMeeting(50L,
                new CreateMeetingRequest("Sync", null, 1L, Set.of())))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already booked");
        verify(meetingRepository, never()).save(any());
    }

    @Test
    void rejectsBookingOnBusyButUnbookedSlot() {
        slot.setStatus(SlotStatus.BUSY);
        when(slotRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(slot));

        assertThatThrownBy(() -> service.bookMeeting(50L,
                new CreateMeetingRequest("Sync", null, 1L, Set.of())))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Only FREE");
    }

    @Test
    void rejectsBlankTitle() {
        assertThatThrownBy(() -> service.bookMeeting(50L,
                new CreateMeetingRequest("   ", null, 1L, Set.of())))
                .isInstanceOf(InvalidSlotException.class);
    }

    @Test
    void rejectsMissingOrganizer() {
        when(slotRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(slot));
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.bookMeeting(50L,
                new CreateMeetingRequest("Sync", null, 99L, Set.of())))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void rejectsMissingParticipant() {
        when(slotRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(slot));
        when(userRepository.findById(1L)).thenReturn(Optional.of(organizer));
        // Only organizer found, participant 2 not in the result
        when(userRepository.findAllByIdIn(any())).thenReturn(List.of(organizer));

        assertThatThrownBy(() -> service.bookMeeting(50L,
                new CreateMeetingRequest("Sync", null, 1L, Set.of(2L))))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void cancelMeetingFreesSlot() {
        Meeting meeting = Meeting.builder().id(999L).slot(slot).organizer(organizer).title("Sync").build();
        slot.setStatus(SlotStatus.BUSY);
        slot.setMeeting(meeting);
        when(meetingRepository.findById(999L)).thenReturn(Optional.of(meeting));

        service.cancelMeeting(999L);

        assertThat(slot.getStatus()).isEqualTo(SlotStatus.FREE);
        assertThat(slot.getMeeting()).isNull();
        verify(meetingRepository).delete(meeting);
    }
}
