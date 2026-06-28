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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Slot s WHERE s.id = :id")
    Optional<Slot> findByIdForUpdate(@Param("id") Long id);

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


    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
           SELECT s FROM Slot s
           WHERE s.calendar.id = :calendarId
             AND s.startTime   = :start
             AND s.endTime     = :end
             AND s.status      = com.minidoodle.domain.SlotStatus.FREE
             AND s.meeting IS NULL
           """)
    Optional<Slot> findExactFreeMatch(@Param("calendarId") Long calendarId,
                                      @Param("start") Instant start,
                                      @Param("end") Instant end);



    @Query("""
           SELECT s FROM Slot s
           WHERE s.calendar.id = :calendarId
             AND s.status      = com.minidoodle.domain.SlotStatus.FREE
             AND s.meeting IS NULL
             AND s.startTime   < :end
             AND s.endTime     > :start
           ORDER BY s.startTime
           """)
    List<Slot> findFreeSlotsInRange(@Param("calendarId") Long calendarId,
                                    @Param("start") Instant start,
                                    @Param("end") Instant end);
}