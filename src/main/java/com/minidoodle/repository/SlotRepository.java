package com.minidoodle.repository;

import com.minidoodle.domain.Slot;
import com.minidoodle.domain.SlotStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface SlotRepository extends JpaRepository<Slot, Long> {

    /**
     * Pessimistic write lock for safe state transitions (e.g. FREE -> booked).
     * Use sparingly - the DB exclusion constraint handles the no-overlap invariant
     * even without locking. This is for status integrity on the slot itself.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Slot s WHERE s.id = :id")
    Optional<Slot> findByIdForUpdate(@Param("id") Long id);

    /**
     * Range query for a single calendar with optional status filter.
     * Uses the (calendar_id, start_time, end_time) compound index.
     * Half-open semantics: a slot is included when it overlaps [from, to).
     */
    @Query("""
           SELECT s FROM Slot s
           WHERE s.calendar.id = :calendarId
             AND s.startTime < :to
             AND s.endTime   > :from
             AND (:status IS NULL OR s.status = :status)
           """)
    Page<Slot> findInRange(@Param("calendarId") Long calendarId,
                           @Param("from") Instant from,
                           @Param("to") Instant to,
                           @Param("status") SlotStatus status,
                           Pageable pageable);

    /**
     * Bulk fetch for aggregated views across multiple calendars.
     * One query, results grouped client-side. Uses the same index.
     */
    @Query("""
           SELECT s FROM Slot s
           WHERE s.calendar.id IN :calendarIds
             AND s.startTime < :to
             AND s.endTime   > :from
             AND (:status IS NULL OR s.status = :status)
           ORDER BY s.calendar.id, s.startTime
           """)
    List<Slot> findInRangeForCalendars(@Param("calendarIds") Collection<Long> calendarIds,
                                       @Param("from") Instant from,
                                       @Param("to") Instant to,
                                       @Param("status") SlotStatus status);

    /**
     * Defensive overlap pre-check.
     * Note: the database EXCLUDE constraint is the authoritative guarantee. This
     * exists so we can return a clean 409 Conflict before the constraint fires.
     */
    @Query("""
           SELECT (COUNT(s) > 0) FROM Slot s
           WHERE s.calendar.id = :calendarId
             AND s.startTime < :end
             AND s.endTime   > :start
             AND (:excludeId IS NULL OR s.id <> :excludeId)
           """)
    boolean existsOverlapping(@Param("calendarId") Long calendarId,
                              @Param("start") Instant start,
                              @Param("end") Instant end,
                              @Param("excludeId") Long excludeId);
}
