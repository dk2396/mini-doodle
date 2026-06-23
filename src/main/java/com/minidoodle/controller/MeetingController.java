package com.minidoodle.controller;

import com.minidoodle.dto.request.CreateMeetingRequest;
import com.minidoodle.dto.response.MeetingResponse;
import com.minidoodle.service.MeetingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@Tag(name = "Meetings", description = "Convert slots into meetings with participants.")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MeetingController {

    private final MeetingService meetingService;

    @Operation(summary = "Convert a slot into a meeting (books the slot atomically)")
    @PostMapping("/slots/{slotId}/meetings")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<MeetingResponse> book(@PathVariable Long slotId,
                                                @Valid @RequestBody CreateMeetingRequest req) {
        MeetingResponse m = meetingService.bookMeeting(slotId, req);
        return ResponseEntity.created(URI.create("/api/v1/meetings/" + m.id())).body(m);
    }

    @Operation(summary = "Get a meeting by id")
    @GetMapping("/meetings/{meetingId}")
    public MeetingResponse get(@PathVariable Long meetingId) {
        return meetingService.getMeeting(meetingId);
    }

    @Operation(summary = "Cancel a meeting (frees the underlying slot)")
    @DeleteMapping("/meetings/{meetingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable Long meetingId) {
        meetingService.cancelMeeting(meetingId);
    }
}
