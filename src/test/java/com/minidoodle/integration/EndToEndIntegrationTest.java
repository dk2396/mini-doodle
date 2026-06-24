package com.minidoodle.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minidoodle.domain.SlotStatus;
import com.minidoodle.dto.request.*;
import com.minidoodle.dto.response.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class EndToEndIntegrationTest extends PostgresIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    private Instant minute(int offsetMinutes) {
        return Instant.now().plus(offsetMinutes, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES);
    }

    @Test
    void fullBookingFlow() throws Exception {
        // Create organizer
        UserResponse organizer = createUser("organizer-" + System.nanoTime() + "@x.com", "Org");
        // Create participant
        UserResponse participant = createUser("participant-" + System.nanoTime() + "@x.com", "Part");

        // Create slot for organizer
        Instant start = minute(60);
        Instant end = minute(90);
        SlotResponse slot = createSlot(organizer.id(), start, end);

        assertThat(slot.status()).isEqualTo(SlotStatus.FREE);
        assertThat(slot.meetingId()).isNull();

        // Convert to meeting
        CreateMeetingRequest meetingReq = new CreateMeetingRequest(
                "Project sync", "Quarterly review", organizer.id(), Set.of(participant.id()));
        MvcResult mr = mvc.perform(post("/api/v1/slots/" + slot.id() + "/meetings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(meetingReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Project sync"))
                .andReturn();
        MeetingResponse meeting = om.readValue(mr.getResponse().getContentAsString(), MeetingResponse.class);

        assertThat(meeting.participants()).hasSize(2); // organizer auto-added

        // Verify slot is now BUSY
        mvc.perform(get("/api/v1/slots/" + slot.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BUSY"))
                .andExpect(jsonPath("$.meetingId").value(meeting.id()));

        // Cannot re-book
        mvc.perform(post("/api/v1/slots/" + slot.id() + "/meetings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(meetingReq)))
                .andExpect(status().isConflict());

        // Cannot delete booked slot directly
        mvc.perform(delete("/api/v1/slots/" + slot.id()))
                .andExpect(status().isConflict());

        // Cancel meeting frees the slot
        mvc.perform(delete("/api/v1/meetings/" + meeting.id()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/slots/" + slot.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FREE"))
                .andExpect(jsonPath("$.meetingId").isEmpty());
    }

    @Test
    void overlappingSlotIsRejectedByDb() throws Exception {
        UserResponse user = createUser("user-" + System.nanoTime() + "@x.com", "U");

        Instant start = minute(120);
        Instant end = minute(180);
        createSlot(user.id(), start, end);

        // exact overlap
        mvc.perform(post("/api/v1/users/" + user.id() + "/slots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new CreateSlotRequest(start, end, null))))
                .andExpect(status().isConflict());

        // partial overlap on left
        mvc.perform(post("/api/v1/users/" + user.id() + "/slots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(
                                new CreateSlotRequest(start.minus(15, ChronoUnit.MINUTES),
                                                      start.plus(15, ChronoUnit.MINUTES), null))))
                .andExpect(status().isConflict());

        // adjacent (touching boundaries) is allowed - half-open ranges
        mvc.perform(post("/api/v1/users/" + user.id() + "/slots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(
                                new CreateSlotRequest(end, end.plus(30, ChronoUnit.MINUTES), null))))
                .andExpect(status().isCreated());
    }

    @Test
    void duplicateUserEmailIsRejected() throws Exception {
        String email = "dup-" + System.nanoTime() + "@x.com";
        createUser(email, "First");
        mvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new CreateUserRequest(email, "Second", null))))
                .andExpect(status().isConflict());
    }

    @Test
    void invalidSlotTimesAreRejected() throws Exception {
        UserResponse user = createUser("inv-" + System.nanoTime() + "@x.com", "U");

        // end before start
        mvc.perform(post("/api/v1/users/" + user.id() + "/slots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(
                                new CreateSlotRequest(minute(60), minute(30), null))))
                .andExpect(status().isBadRequest());

        // too short
        mvc.perform(post("/api/v1/users/" + user.id() + "/slots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(
                                new CreateSlotRequest(minute(60), minute(61), null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listSlotsInRange() throws Exception {
        UserResponse user = createUser("list-" + System.nanoTime() + "@x.com", "U");
        Instant base = minute(10);
        createSlot(user.id(), base, base.plus(30, ChronoUnit.MINUTES));
        createSlot(user.id(), base.plus(60, ChronoUnit.MINUTES), base.plus(90, ChronoUnit.MINUTES));

        mvc.perform(get("/api/v1/users/" + user.id() + "/slots")
                        .param("from", base.toString())
                        .param("to", base.plus(2, ChronoUnit.HOURS).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void aggregateAvailability() throws Exception {
        UserResponse a = createUser("a-" + System.nanoTime() + "@x.com", "A");
        UserResponse b = createUser("b-" + System.nanoTime() + "@x.com", "B");
        Instant base = minute(15);
        createSlot(a.id(), base, base.plus(30, ChronoUnit.MINUTES));
        createSlot(b.id(), base.plus(60, ChronoUnit.MINUTES), base.plus(90, ChronoUnit.MINUTES));

        AggregateAvailabilityRequest req = new AggregateAvailabilityRequest(
                Set.of(a.id(), b.id()), base, base.plus(2, ChronoUnit.HOURS), SlotStatus.FREE);

        mvc.perform(post("/api/v1/availability/aggregate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slotsByUser." + a.id() + ".length()").value(1))
                .andExpect(jsonPath("$.slotsByUser." + b.id() + ".length()").value(1));
    }

    @Test
    void bulkCreateSlots() throws Exception {
        UserResponse user = createUser("bulk-" + System.nanoTime() + "@x.com", "U");
        Instant base = minute(15);
        List<CreateSlotRequest> slots = List.of(
                new CreateSlotRequest(base, base.plus(30, ChronoUnit.MINUTES), null),
                new CreateSlotRequest(base.plus(30, ChronoUnit.MINUTES), base.plus(60, ChronoUnit.MINUTES), null),
                new CreateSlotRequest(base.plus(60, ChronoUnit.MINUTES), base.plus(90, ChronoUnit.MINUTES), null)
        );
        mvc.perform(post("/api/v1/users/" + user.id() + "/slots/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new BulkCreateSlotsRequest(slots))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(3));
    }

    // ---- helpers ----

    private UserResponse createUser(String email, String name) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new CreateUserRequest(email, name, null))))
                .andExpect(status().isCreated())
                .andReturn();
        return om.readValue(r.getResponse().getContentAsString(), UserResponse.class);
    }

    private SlotResponse createSlot(Long userId, Instant start, Instant end) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/users/" + userId + "/slots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new CreateSlotRequest(start, end, null))))
                .andExpect(status().isCreated())
                .andReturn();
        return om.readValue(r.getResponse().getContentAsString(), SlotResponse.class);
    }
}
