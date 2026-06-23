package com.minidoodle.controller;

import com.minidoodle.domain.SlotStatus;
import com.minidoodle.dto.request.BulkCreateSlotsRequest;
import com.minidoodle.dto.request.CreateSlotRequest;
import com.minidoodle.dto.request.UpdateSlotRequest;
import com.minidoodle.dto.response.PagedResponse;
import com.minidoodle.dto.response.SlotResponse;
import com.minidoodle.service.SlotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;
import java.util.List;

@Tag(name = "Slots", description = "Time slot management for a user's calendar.")
@RestController
@RequiredArgsConstructor
public class SlotController {

    private final SlotService slotService;

    @Operation(summary = "Create a slot in a user's calendar")
    @PostMapping("/api/v1/users/{userId}/slots")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<SlotResponse> create(@PathVariable Long userId,
                                               @Valid @RequestBody CreateSlotRequest req) {
        SlotResponse s = slotService.createSlot(userId, req);
        return ResponseEntity.created(URI.create("/api/v1/slots/" + s.id())).body(s);
    }

    @Operation(summary = "Bulk-create slots (up to 500) in a user's calendar")
    @PostMapping("/api/v1/users/{userId}/slots/bulk")
    @ResponseStatus(HttpStatus.CREATED)
    public List<SlotResponse> createBulk(@PathVariable Long userId,
                                         @Valid @RequestBody BulkCreateSlotsRequest req) {
        return slotService.createSlotsBulk(userId, req);
    }

    @Operation(summary = "List a user's slots in a time window (paginated). Both 'from' and 'to' are ISO-8601 instants.")
    @GetMapping("/api/v1/users/{userId}/slots")
    public PagedResponse<SlotResponse> list(
            @PathVariable Long userId,
            @Parameter(description = "ISO-8601 instant, inclusive lower bound")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "ISO-8601 instant, exclusive upper bound")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @Parameter(description = "Optional status filter: FREE or BUSY")
            @RequestParam(required = false) SlotStatus status,
            Pageable pageable) {
        return PagedResponse.from(slotService.listSlots(userId, from, to, status, pageable));
    }

    @Operation(summary = "Get a single slot by id")
    @GetMapping("/api/v1/slots/{slotId}")
    public SlotResponse get(@PathVariable Long slotId) {
        return slotService.getSlot(slotId);
    }

    @Operation(summary = "Update a slot (partial). Booked slots cannot be updated.")
    @PatchMapping("/api/v1/slots/{slotId}")
    public SlotResponse update(@PathVariable Long slotId, @RequestBody UpdateSlotRequest req) {
        return slotService.updateSlot(slotId, req);
    }

    @Operation(summary = "Delete a slot. Booked slots must be cancelled first.")
    @DeleteMapping("/api/v1/slots/{slotId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long slotId) {
        slotService.deleteSlot(slotId);
    }
}
