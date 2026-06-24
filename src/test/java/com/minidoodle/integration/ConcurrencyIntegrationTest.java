package com.minidoodle.integration;

import com.minidoodle.domain.SlotStatus;
import com.minidoodle.dto.request.CreateMeetingRequest;
import com.minidoodle.dto.request.CreateSlotRequest;
import com.minidoodle.dto.request.CreateUserRequest;
import com.minidoodle.exception.ConflictException;
import com.minidoodle.exception.SlotOverlapException;
import com.minidoodle.service.MeetingService;
import com.minidoodle.service.SlotService;
import com.minidoodle.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the DB-level invariants hold under contention. These tests need a
 * real Postgres - they exercise the GIST EXCLUDE constraint and the slot
 * row lock that make the system safe.
 */
class ConcurrencyIntegrationTest extends PostgresIntegrationTest {

    @Autowired UserService userService;
    @Autowired SlotService slotService;
    @Autowired MeetingService meetingService;

    @Test
    void onlyOneOfNConcurrentBookingsOnTheSameSlotSucceeds() throws Exception {
        var organizer = userService.createUser(new CreateUserRequest(
                "conc-org-" + System.nanoTime() + "@x.com", "Org", null));

        Instant start = Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES);
        Instant end   = start.plus(30, ChronoUnit.MINUTES);
        var slot = slotService.createSlot(organizer.id(), new CreateSlotRequest(start, end, null));

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go    = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        CompletionService<Void> ecs = new ExecutorCompletionService<>(pool);

        for (int i = 0; i < threads; i++) {
            ecs.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    meetingService.bookMeeting(slot.id(),
                            new CreateMeetingRequest("Race", null, organizer.id(), Set.of()));
                    success.incrementAndGet();
                } catch (ConflictException e) {
                    conflict.incrementAndGet();
                } catch (Exception e) {
                    other.incrementAndGet();
                }
                return null;
            });
        }
        ready.await();
        go.countDown();
        for (int i = 0; i < threads; i++) ecs.take();
        pool.shutdown();
        pool.awaitTermination(20, TimeUnit.SECONDS);

        assertThat(success.get()).isEqualTo(1);
        assertThat(success.get() + conflict.get() + other.get()).isEqualTo(threads);
    }

    @Test
    void concurrentOverlappingSlotCreationsResultInExactlyOneSurvivor() throws Exception {
        var user = userService.createUser(new CreateUserRequest(
                "conc-overlap-" + System.nanoTime() + "@x.com", "U", null));

        Instant start = Instant.now().plus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES);
        Instant end   = start.plus(60, ChronoUnit.MINUTES);

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go    = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        CompletionService<Void> ecs = new ExecutorCompletionService<>(pool);

        for (int i = 0; i < threads; i++) {
            ecs.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    slotService.createSlot(user.id(), new CreateSlotRequest(start, end, null));
                    success.incrementAndGet();
                } catch (SlotOverlapException | DataIntegrityViolationException e) {
                    rejected.incrementAndGet();
                } catch (Exception e) {
                    rejected.incrementAndGet();
                }
                return null;
            });
        }
        ready.await();
        go.countDown();
        for (int i = 0; i < threads; i++) ecs.take();
        pool.shutdown();
        pool.awaitTermination(20, TimeUnit.SECONDS);

        assertThat(success.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(threads - 1);
    }
}
