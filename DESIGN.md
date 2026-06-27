# Design & Trade-offs

This document explains the decisions behind Mini Doodle. The brief says the goal is to surface design judgement, so this is the file to read for that.

---

## Headline decisions

1. **No-overlap is a database invariant**, not an application check — Postgres `EXCLUDE` constraint on `tstzrange` per calendar.
2. **Default-unavailable booking** ("Doodle-style"): invitees must have advertised a matching FREE slot to be bookable.
3. **Multi-attendee slot binding** (V2): a meeting binds N slots, one per attendee, via `slots.meeting_id`. Aggregate availability returns the truth for invitees.
4. **Layered concurrency control**: DB exclusion + pessimistic row lock on state transitions + optimistic `@Version` on edits.
5. **Half-open `[start, end)` ranges** everywhere — matches iCalendar/Google/Outlook.

The rest of the doc explains *why* each one, what it trades away, and where the boundaries are. There's an honest "what I'd do differently" section at the end.

---

## 1. Scope

**In scope:** slot CRUD, slot → meeting conversion, free/busy aggregation, V1 + V2 migration with a real schema evolution, integration tests against a real Postgres.

**Out of scope** (and why):

- **Auth.** Endpoints are open; the user model is ready for an `owner_id` audit field. Real auth = follow-up.
- **Participant response endpoint.** `MeetingParticipant.responseStatus` is set correctly at booking (organizer = ACCEPTED, invitees = PENDING), but there's no `PATCH` to change it. State machine sketched in code; endpoint deferred.
- **Notifications, ICS export, recurrence.** Recurrence in particular is its own design exercise (RRULE expansion vs. materialization).
- **Soft delete & audit trail.** Hard-delete now. Production would soft-delete and emit audit events.
- **Multi-region, distributed deployment.**

---

## 2. The core invariant: no overlap, enforced by the database

The strongest invariant in a scheduling system is *no two slots overlap inside the same calendar*. Three places to enforce it:

| Where | Strength | Cost |
|---|---|---|
| Application | Weakest — TOCTOU race between SELECT and INSERT | Cheapest |
| Distributed lock | Strong, but a new fault domain | Infra dependency on the write path |
| **Database (chosen)** | Strongest — impossible to violate regardless of process count or partial failures | Locked to Postgres |

The constraint:

```sql
CONSTRAINT slot_no_overlap EXCLUDE USING gist (
    calendar_id WITH =,
    tstzrange(start_time, end_time, '[)') WITH &&
)
```

The application keeps an `existsOverlapping` pre-check so the common case returns a clean 409 with a clear message, but it is *not* the source of truth — the DB constraint is. The `GlobalExceptionHandler` catches the Postgres violation by constraint name and translates it to 409.

**Trade-off**: vendor lock-in on Postgres. The alternatives are either much more code (`SERIALIZABLE` + retry) or a new infra dependency (distributed lock), both worse than coupling to a feature Postgres has had since 9.0.

This single constraint transitively covers the entire correctness story for the slot table: no two FREE slots overlap (you can't be free twice at once), no two BUSY slots overlap (you can't be in two meetings at once), no FREE/BUSY overlap. Combined with V2's `UNIQUE (meeting_id, calendar_id)`, a single meeting also can't have two slots on the same calendar.

---

## 3. Booking semantics: default-unavailable (Model B)

Two valid product shapes for a calendar:

- **Default-available** (Outlook, Google): users are bookable at any time unless explicitly blocked.
- **Default-unavailable** (Doodle, Calendly): users are bookable only at times they have explicitly advertised as FREE.

We chose default-unavailable because the brief literally says *"users define available slots which can later be converted into meetings"* and the product is called *"mini Doodle"*. Both rule out the Outlook model.

Concretely:
- Organizer pre-creates a FREE slot. Invitees must also have a FREE slot at the **exact** time before they can be invited.
- Booking claims everyone's matching FREE slot (FREE → BUSY) and binds them all to the same meeting via `slots.meeting_id`.
- No matching slot → 409 with a message naming the user and the time range.
- Cancellation returns every bound slot to FREE — nothing is deleted. Each calendar reverts to exactly what it looked like before the booking.

**What we explicitly do not do**: split FREE slots. If an invitee has FREE 09:00–10:00 and the meeting is 09:00–09:30, the booking fails. Slot splitting is meaningful UX but it's its own design exercise (timezones, recurring slots) and out of scope.

**Trade-off**: stricter contract than Outlook — a user who has done nothing in the system cannot be invited. Correct for Doodle, wrong for a corporate calendar. A future default-available mode is a per-calendar setting away; the data model accommodates it (drop the exact-match requirement in `MeetingService`).

---

## 4. Concurrency model

Three distinct hazards, three defenses.

**Slot creation overlap.** Two writes that would create overlapping slots in the same calendar. Defended by the GIST `EXCLUDE` constraint (§2). Verified by `ConcurrencyIntegrationTest.concurrentOverlappingSlotCreationsResultInExactlyOneSurvivor`.

**Double-booking the same slot.** Two writes trying to bind the same FREE slot to different meetings. Pessimistic `SELECT FOR UPDATE` on the slot row during booking serializes them at the DB; the second writer sees `status=BUSY` after the first commits and returns 409. V2's `UNIQUE (meeting_id, calendar_id)` is the secondary defense. Verified by `ConcurrencyIntegrationTest.onlyOneOfNConcurrentBookingsOnTheSameSlotSucceeds`.

**Concurrent edits.** Two `PATCH` requests on the same slot. JPA `@Version` optimistic locking; the loser gets `OptimisticLockingFailureException` → 409. No pessimistic lock here, because hot calendars would suffer.

**Trade-off**: mixing optimistic and pessimistic locking on the same table is intentional but adds mental overhead. The split: pessimistic for state transitions (FREE → BUSY), optimistic for everything else.

---

## 5. Half-open `[start, end)` ranges

`09:00–10:00` and `10:00–11:00` do **not** overlap. Convention in every serious calendar system (iCalendar's `DTEND` is exclusive; Outlook and Google both follow). The DB literal `tstzrange(start, end, '[)')` makes the convention explicit and queryable. All overlap checks in the service layer use the same shape.

---

## 6. Performance and scaling

At the stated load — hundreds of users, thousands of slots — Postgres on a laptop handles it without breaking a sweat. The indexes that matter:

- `(calendar_id, start_time, end_time)` btree for the workhorse range query.
- GIST on `tstzrange(start_time, end_time, '[)')`, which backs the `EXCLUDE` constraint and serves overlap queries.

The aggregate-availability path is a single `IN` query bounded by the input user set, grouped in Java — one DB roundtrip regardless of how many users are requested.

A Redis cache sits in front of aggregate availability with a 30s TTL and blanket eviction on write. Honest assessment: overkill at this scale; could be removed without measurable impact. See *What I'd do differently*.

**Path to 10× and 100×**, in order:
1. Read replicas via `LazyConnectionDataSourceProxy` + `@Transactional(readOnly=true)` routing.
2. pgBouncer for connection multiplexing.
3. Shard by `calendar_id` — every relevant index has it as a prefix; no cross-calendar query except aggregate availability, which is naturally batchable per shard.
4. Outbox + Kafka once notifications and audit are in scope.

---

## 7. Operability

- **Health and metrics** at `/actuator/health` and `/actuator/prometheus`. Standard Spring Boot wiring.
- **Structured logs** with `traceId`/`spanId` placeholders ready for OpenTelemetry.
- **Errors are uniform**: a single `@RestControllerAdvice` translates every domain and persistence exception to an `ErrorResponse` envelope (timestamp, status, error, message, path, optional field errors). The `slot_no_overlap` constraint name is caught by message and translated to 409, not 500.
- **Migrations are versioned**: V1 = initial schema, V2 = multi-attendee refactor. Applied migrations are immutable.

---

## 8. Calendar as a domain concept

The brief says: *"Calendar as the term in the task should be present only in the domain in the service."* Honored strictly: `Calendar` is a JPA entity, owned 1:1 by each user, and never referenced in any DTO, request path, or controller. The benefit of having it as an entity is that the no-overlap invariant has a stable foreign key (`calendar_id`) to scope on, which is what the GIST `EXCLUDE` needs.

---

