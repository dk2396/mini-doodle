package com.minidoodle.repository;

import com.minidoodle.domain.Calendar;
import com.minidoodle.domain.Slot;
import com.minidoodle.domain.SlotStatus;
import com.minidoodle.domain.User;
import com.minidoodle.integration.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class SlotRepositoryIntegrationTest extends PostgresIntegrationTest {

    @Autowired SlotRepository slotRepository;
    @Autowired CalendarRepository calendarRepository;
    @Autowired UserRepository userRepository;

    User user;
    Calendar calendar;

    @BeforeEach
    void seed() {
        user = userRepository.save(User.builder()
                .email("repo-" + System.nanoTime() + "@x.com").displayName("R").build());
        calendar = calendarRepository.save(Calendar.builder().user(user).timezone("UTC").build());
    }

    @Test
    void rangeQueryReturnsOverlappingSlots() {
        Instant base = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES);
        slotRepository.saveAll(List.of(
                slot(base, base.plus(30, ChronoUnit.MINUTES)),
                slot(base.plus(60, ChronoUnit.MINUTES), base.plus(90, ChronoUnit.MINUTES)),
                slot(base.plus(180, ChronoUnit.MINUTES), base.plus(210, ChronoUnit.MINUTES))
        ));

        var page = slotRepository.findInRange(calendar.getId(),
                base, base.plus(2, ChronoUnit.HOURS), null,
                PageRequest.of(0, 50));

        // First two are in [base, base+2h); third one is not
        assertThat(page.getContent()).hasSize(2);
    }

    @Test
    void existsOverlappingDetectsConflict() {
        Instant base = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES);
        slotRepository.save(slot(base, base.plus(30, ChronoUnit.MINUTES)));

        assertThat(slotRepository.existsOverlapping(calendar.getId(),
                base.plus(15, ChronoUnit.MINUTES),
                base.plus(45, ChronoUnit.MINUTES), null)).isTrue();

        // Touching boundary - not an overlap
        assertThat(slotRepository.existsOverlapping(calendar.getId(),
                base.plus(30, ChronoUnit.MINUTES),
                base.plus(60, ChronoUnit.MINUTES), null)).isFalse();
    }

    private Slot slot(Instant start, Instant end) {
        return Slot.builder()
                .calendar(calendar).startTime(start).endTime(end).status(SlotStatus.FREE).build();
    }
}
