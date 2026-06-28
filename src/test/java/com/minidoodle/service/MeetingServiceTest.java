package com.minidoodle.service;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MeetingServiceTest {

    @Mock MeetingRepository meetingRepository;
    @Mock SlotRepository slotRepository;
    @Mock UserRepository userRepository;
    @Mock CalendarRepository calendarRepository;
    @Mock DomainMapper mapper;

    @InjectMocks MeetingService service;

    User organizer;
    User invitee;
    Calendar organizerCalendar;
    Calendar inviteeCalendar;
    Slot organizerSlot;

    @BeforeEach
    void setUp() {
        organizer = User.builder().id(1L).email("o@x.com").displayName("Org").build();
        invitee   = User.builder().id(2L).email("i@x.com").displayName("Inv").build();
        organizerCalendar = Calendar.builder().id(10L).user(organizer).timezone("UTC").build();
        inviteeCalendar   = Calendar.builder().id(20L).user(invitee).timezone("UTC").build();

        Instant start = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES);
        Instant end   = start.plus(30, ChronoUnit.MINUTES);
        organizerSlot = Slot.builder()
                .id(100L).calendar(organizerCalendar)
                .startTime(start).endTime(end)
                .status(SlotStatus.FREE)
                .build();
    }

    @Test
    void booksMeetingClaimingInviteesExistingFreeSlot() {
        // Model B requires the invitee to have an exact-match FREE slot.
        Slot inviteeSlot = Slot.builder()
                .id(200L).calendar(inviteeCalendar)
                .startTime(organizerSlot.getStartTime())
                .endTime(organizerSlot.getEndTime())
                .status(SlotStatus.FREE).build();

        when(slotRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(organizerSlot));
        when(userRepository.findById(1L)).thenReturn(Optional.of(organizer));
        when(userRepository.findAllByIdIn(any())).thenReturn(List.of(organizer, invitee));
        when(calendarRepository.findByUserId(2L)).thenReturn(Optional.of(inviteeCalendar));
        when(slotRepository.findExactFreeMatch(eq(20L), any(), any())).thenReturn(Optional.of(inviteeSlot));
        when(meetingRepository.save(any(Meeting.class))).thenAnswer(inv -> {
            Meeting m = inv.getArgument(0); m.setId(999L); return m;
        });
        when(mapper.toMeetingResponse(any(Meeting.class))).thenAnswer(inv -> {
            Meeting m = inv.getArgument(0);
            return new MeetingResponse(m.getId(), m.getOrganizer().getId(), m.getTitle(),
                    m.getDescription(), organizerSlot.getStartTime(), organizerSlot.getEndTime(),
                    List.of(), Instant.now());
        });

        MeetingResponse resp = service.bookMeeting(100L,
                new CreateMeetingRequest("Sync", null, 1L, Set.of(2L)));

        assertThat(resp.id()).isEqualTo(999L);
        // Both slots are now BUSY and bound to the meeting
        assertThat(organizerSlot.getStatus()).isEqualTo(SlotStatus.BUSY);
        assertThat(organizerSlot.getMeeting()).isNotNull();
        assertThat(inviteeSlot.getStatus()).isEqualTo(SlotStatus.BUSY);
        assertThat(inviteeSlot.getMeeting()).isNotNull();
        // No new slot was created - we claimed an existing one
        verify(slotRepository, never()).save(any(Slot.class));
    }

    @Test
    void organizerIsAutoAcceptedInvitedAttendeesArePending() {
        Slot inviteeSlot = Slot.builder()
                .id(200L).calendar(inviteeCalendar)
                .startTime(organizerSlot.getStartTime())
                .endTime(organizerSlot.getEndTime())
                .status(SlotStatus.FREE).build();

        when(slotRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(organizerSlot));
        when(userRepository.findById(1L)).thenReturn(Optional.of(organizer));
        when(userRepository.findAllByIdIn(any())).thenReturn(List.of(organizer, invitee));
        when(calendarRepository.findByUserId(2L)).thenReturn(Optional.of(inviteeCalendar));
        when(slotRepository.findExactFreeMatch(eq(20L), any(), any())).thenReturn(Optional.of(inviteeSlot));
        when(meetingRepository.save(any(Meeting.class))).thenAnswer(inv -> {
            Meeting m = inv.getArgument(0); m.setId(999L); return m;
        });
        when(mapper.toMeetingResponse(any(Meeting.class))).thenAnswer(inv ->
                new MeetingResponse(999L, 1L, "Sync", null,
                        organizerSlot.getStartTime(), organizerSlot.getEndTime(),
                        List.of(), Instant.now()));

        service.bookMeeting(100L, new CreateMeetingRequest("Sync", null, 1L, Set.of(2L)));

        org.mockito.ArgumentCaptor<Meeting> captor = org.mockito.ArgumentCaptor.forClass(Meeting.class);
        verify(meetingRepository).save(captor.capture());
        Meeting saved = captor.getValue();
        assertThat(saved.getParticipants()).hasSize(2);
        var orgPart = saved.getParticipants().stream()
                .filter(p -> p.getUser().getId().equals(1L)).findFirst().orElseThrow();
        var invPart = saved.getParticipants().stream()
                .filter(p -> p.getUser().getId().equals(2L)).findFirst().orElseThrow();
        assertThat(orgPart.getResponseStatus()).isEqualTo(MeetingParticipant.ResponseStatus.ACCEPTED);
        assertThat(invPart.getResponseStatus()).isEqualTo(MeetingParticipant.ResponseStatus.PENDING);
    }

    @Test
    void rejectsBookingAlreadyBookedSlot() {
        organizerSlot.setStatus(SlotStatus.BUSY);
        organizerSlot.setMeeting(Meeting.builder().id(7L).build());
        when(slotRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(organizerSlot));

        assertThatThrownBy(() -> service.bookMeeting(100L,
                new CreateMeetingRequest("Sync", null, 1L, Set.of())))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already booked");
        verify(meetingRepository, never()).save(any());
    }

    @Test
    void rejectsBookingOnBusyButUnbookedSlot() {
        organizerSlot.setStatus(SlotStatus.BUSY);
        when(slotRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(organizerSlot));

        assertThatThrownBy(() -> service.bookMeeting(100L,
                new CreateMeetingRequest("Sync", null, 1L, Set.of())))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Only FREE");
    }

    @Test
    void rejectsBlankTitle() {
        assertThatThrownBy(() -> service.bookMeeting(100L,
                new CreateMeetingRequest("   ", null, 1L, Set.of())))
                .isInstanceOf(InvalidSlotException.class);
    }

    @Test
    void rejectsWhenSlotDoesNotBelongToOrganizer() {
        // Slot belongs to user 1 (organizerCalendar.user). Try to book as user 2.
        when(slotRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(organizerSlot));
        when(userRepository.findById(2L)).thenReturn(Optional.of(invitee));

        assertThatThrownBy(() -> service.bookMeeting(100L,
                new CreateMeetingRequest("Sync", null, 2L, Set.of())))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("does not belong to organizer");
    }

    @Test
    void rejectsMissingOrganizer() {
        when(slotRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(organizerSlot));
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.bookMeeting(100L,
                new CreateMeetingRequest("Sync", null, 99L, Set.of())))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void rejectsMissingParticipant() {
        when(slotRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(organizerSlot));
        when(userRepository.findById(1L)).thenReturn(Optional.of(organizer));
        when(userRepository.findAllByIdIn(any())).thenReturn(List.of(organizer)); // invitee missing

        assertThatThrownBy(() -> service.bookMeeting(100L,
                new CreateMeetingRequest("Sync", null, 1L, Set.of(2L))))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void rejectsWhenInviteeHasNotAdvertisedAvailability() {
        // Under Model B, the failure case is simply "invitee has no exact-match
        // FREE slot." Whether their calendar is empty or contains other slots
        // doesn't matter - if they didn't pre-advertise THIS exact range, the
        // booking fails.
        when(slotRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(organizerSlot));
        when(userRepository.findById(1L)).thenReturn(Optional.of(organizer));
        when(userRepository.findAllByIdIn(any())).thenReturn(List.of(organizer, invitee));
        when(calendarRepository.findByUserId(2L)).thenReturn(Optional.of(inviteeCalendar));
        when(slotRepository.findExactFreeMatch(eq(20L), any(), any())).thenReturn(Optional.empty());
        // No partial availability either - calendar empty for this range
        when(slotRepository.findFreeSlotsInRange(eq(20L), any(), any())).thenReturn(List.of());
        when(meetingRepository.save(any(Meeting.class))).thenAnswer(inv -> {
            Meeting m = inv.getArgument(0); m.setId(999L); return m;
        });

        assertThatThrownBy(() -> service.bookMeeting(100L,
                new CreateMeetingRequest("Sync", null, 1L, Set.of(2L))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("has not advertised availability");

        verify(slotRepository, never()).save(any(Slot.class));
    }

    @Test
    void rejectsButReportsPartialAvailabilityWhenInviteeOnlyOverlapsPartially() {
        // Invitee has a 30-min FREE slot, meeting wants 60 min. Booking still
        // fails (Model B never silently stretches commitments), but the error
        // message surfaces the actual free window so the organizer knows what's
        // possible.
        Slot partialSlot = Slot.builder()
                .id(300L).calendar(inviteeCalendar)
                .startTime(organizerSlot.getStartTime())
                .endTime(organizerSlot.getStartTime().plus(15, ChronoUnit.MINUTES))
                .status(SlotStatus.FREE).build();

        when(slotRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(organizerSlot));
        when(userRepository.findById(1L)).thenReturn(Optional.of(organizer));
        when(userRepository.findAllByIdIn(any())).thenReturn(List.of(organizer, invitee));
        when(calendarRepository.findByUserId(2L)).thenReturn(Optional.of(inviteeCalendar));
        when(slotRepository.findExactFreeMatch(eq(20L), any(), any())).thenReturn(Optional.empty());
        when(slotRepository.findFreeSlotsInRange(eq(20L), any(), any())).thenReturn(List.of(partialSlot));
        when(meetingRepository.save(any(Meeting.class))).thenAnswer(inv -> {
            Meeting m = inv.getArgument(0); m.setId(999L); return m;
        });

        assertThatThrownBy(() -> service.bookMeeting(100L,
                new CreateMeetingRequest("Sync", null, 1L, Set.of(2L))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("only partially available")
                .hasMessageContaining("Either shorten the meeting");

        verify(slotRepository, never()).save(any(Slot.class));
    }

    @Test
    void cancelMeetingFreesAllBoundSlots() {
        // Model B: every slot bound to the meeting was explicitly created by
        // its owner, so cancellation frees them ALL - nothing is deleted.
        Slot inviteeSlot = Slot.builder()
                .id(200L).calendar(inviteeCalendar)
                .startTime(organizerSlot.getStartTime())
                .endTime(organizerSlot.getEndTime())
                .status(SlotStatus.BUSY).build();

        Meeting meeting = Meeting.builder().id(999L).organizer(organizer).title("Sync").build();
        meeting.addSlot(organizerSlot);
        meeting.addSlot(inviteeSlot);
        organizerSlot.setStatus(SlotStatus.BUSY);

        when(meetingRepository.findByIdWithDetails(999L)).thenReturn(Optional.of(meeting));

        service.cancelMeeting(999L);

        // Both slots return to FREE state; neither is deleted
        assertThat(organizerSlot.getStatus()).isEqualTo(SlotStatus.FREE);
        assertThat(organizerSlot.getMeeting()).isNull();
        assertThat(inviteeSlot.getStatus()).isEqualTo(SlotStatus.FREE);
        assertThat(inviteeSlot.getMeeting()).isNull();
        verify(slotRepository, never()).delete(any(Slot.class));
        verify(meetingRepository).delete(meeting);
    }
}