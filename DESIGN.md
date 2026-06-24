# Design & Trade-offs

This document captures the design decisions behind Mini Doodle, the trade-offs each one carries, and the alternatives that were considered and rejected. The goal of the challenge is to surface design and tech decision-making, so this is the file to read for that.

---

## 1. Goals and non-goals

**Goals**

- Correctness first: it must be impossible to create overlapping slots or double-book a slot, even under concurrency.
- Scale to "hundreds of users, thousands of slots" comfortably, with a clear path to scale 10–100× further without rewriting the data model.
- Operability: clean error responses, observability hooks, deterministic schema migrations, container-native runtime.
- Production-grade code structure: layered, testable, no leaked persistence concerns above the service layer.

**Non-goals** (out of scope for this exercise, but signposted)

- AuthN/AuthZ. Every endpoint is open — adding Spring Security with JWT + per-user authorization is a follow-up.
- Notifications, ICS export, recurrence rules. Recurrence in particular has substantial design implications (RRULE expansion vs. materialization) and would dwarf the rest.
- Multi-region deployment, leader-elected workers, cross-region replication.

---

## 2. The single most important decision: enforce "no overlap" in the database

The strongest invariant in a scheduling system is *no two slots overlap inside the same calendar*. Almost every bug class in this kind of system traces back to a missing or partial enforcement of that invariant. There are essentially three places to enforce it:

| Where        | Strength                                                       | Cost                                              |
|--------------|----------------------------------------------------------------|---------------------------------------------------|
| Application  | Weakest — TOCTOU race between the `SELECT` and the `INSERT`    | Cheapest, easiest to reason about                 |
| Distributed lock (e.g. Redis Redlock per user) | Strong, but introduces a new fault domain | Adds infrastructure dependency on the write path; tricky reentrancy/expiry semantics |
| **Database (chosen)** | Strongest — the invariant is impossible to violate, regardless of process count, restart timing, or partial failures | Locked to PostgreSQL features (`btree_gist`, `EXCLUDE`) |

The migration uses a GIST exclusion constraint:

```sql
CONSTRAINT slot_no_overlap EXCLUDE USING gist (
    calendar_id WITH =,
    tstzrange(start_time, end_time, '[)') WITH &&
)
```

**Why this is the right call here**

- Application checks alone are unsafe under any concurrency: two pods, two threads in one pod, or even one thread with a slow network can interleave `SELECT exists_overlap` and `INSERT` and produce overlap. We could close that with `SERIALIZABLE` + retry, but per-calendar exclusion is cheaper, deadlock-free, and self-documenting.
- We still keep an application-level pre-check (`existsOverlapping`) so the common case returns `409 Conflict` with a clean message, instead of bubbling a raw `DataIntegrityViolationException`. The DB constraint is the safety net that catches races; the application check is the UX.

**Trade-offs accepted**

- **Vendor lock-in on Postgres.** Worth it — the alternatives are more code, more bugs, and no equivalent in MySQL/MariaDB that doesn't itself rely on locks.
- **Constraint exceptions come as `DataIntegrityViolationException`.** The global handler inspects the message for `slot_no_overlap` and returns 409. Inspecting messages is mildly fragile, but the constraint name is in our own migration so it's stable.

---

## 3. Time range semantics: half-open `[start, end)`

`09:00–10:00` and `10:00–11:00` do **not** overlap. This is the unambiguous convention in every serious calendar system (Outlook, Google Calendar, iCalendar `DTEND` is exclusive). The DB range literal `tstzrange(start, end, '[)')` makes this an explicit, queryable property. All overlap checks in the service layer use the same convention.

**Trade-off**: a "what slot covers exactly this instant" query needs `start <= t < end`, not `start <= t <= end`. The code does this consistently; it's the kind of thing that needs to be picked once and used everywhere.

---

## 4. Concurrency model

There are two distinct concurrency hazards.

### 4a. Overlapping slot creation
Two concurrent requests trying to create slots that overlap each other in the same calendar.
- **Defense**: the GIST `EXCLUDE` constraint. Postgres will block the second writer until the first commits, then reject it. We don't need application-side locks.
- **Verified** by `ConcurrencyIntegrationTest.concurrentOverlappingSlotCreationsResultInExactlyOneSurvivor`.

### 4b. Double-booking a slot into two meetings
Two concurrent requests trying to convert the same FREE slot into a meeting.
- **Defense (primary)**: `meetings.slot_id` has a `UNIQUE` constraint and a foreign key, so a second insert would fail anyway.
- **Defense (UX)**: pessimistic row lock (`SELECT ... FOR UPDATE`) on the slot during booking. Without it, both threads would see `status=FREE`, both would proceed, and the second `INSERT INTO meetings` would fail with a `DataIntegrityViolationException` — correct but ugly. With the row lock, the second thread sees `status=BUSY` after the first commits, and returns a clean `409 Conflict`.
- **Verified** by `ConcurrencyIntegrationTest.onlyOneOfNConcurrentBookingsOnTheSameSlotSucceeds`.

### 4c. Concurrent edits to an unbooked slot
Two concurrent `PATCH` requests on the same slot.
- **Defense**: `@Version` optimistic locking. The second writer gets `OptimisticLockingFailureException` → 409. We do **not** hold pessimistic locks across the request boundary for updates of FREE slots — that would be a footgun for hot calendars.

### Trade-offs
- We mix optimistic (`@Version`) and pessimistic (`SELECT FOR UPDATE`) locking on the same table. This is intentional — pessimistic is reserved for the narrow case where we need the application to see the latest state to enforce a transition rule (FREE → booked), and optimistic for everything else. The cost is a slightly larger mental model.
- The pessimistic lock on a single slot row scopes contention to that row; it doesn't hot-spot the table.

---

## 5. Performance and scaling

### Indexes
- **`(calendar_id, start_time, end_time)`** btree — the workhorse for "give me a user's slots in `[from, to)`".
- **GIST on `tstzrange(start_time, end_time, '[)')`** — used by the EXCLUDE constraint *and* available for range overlap queries.
- **`(status)`** btree — supports filtered listings; low cardinality but useful when ranged-restricted result sets are already small.

### Query shape
The aggregate-availability path uses a single `IN (...)` query bounded by the input user set, then groups in Java. Alternative considered: one query per user. Rejected — N+1 over the HTTP boundary is worth more than the index lookup we'd save.

### Hibernate batch inserts
`spring.jpa.properties.hibernate.jdbc.batch_size=50` plus `order_inserts=true` and `order_updates=true` give us proper JDBC batching on bulk creates. The bulk endpoint caps at 500 to keep a single transaction bounded.

### Read caching
Aggregate availability is cached in Redis with a short TTL (30s default) and the cache key is derived from the full request. Any write (create/update/delete on slots, meetings book/cancel) blanket-evicts the cache via `@CacheEvict(allEntries=true)`. The trade-off here is real:

- **Pro**: handles the hot read path (the question "who is free?" is the headline feature of a Doodle-like product).
- **Con**: a single write evicts everything, which is fine at low write-volume but would need refinement at scale (per-user keys, surgical invalidation, or simply removing the cache and trusting the DB) once writes get bursty.

### Connection pool
HikariCP at 20 connections, sized for moderate parallelism. The Postgres ceiling on `max_connections` is the relevant cap further down — at scale, pgBouncer in transaction-pooling mode in front of the DB is the standard move.

### What we'd reach for next (path to 100×)

1. **Read replicas**. Spring's `LazyConnectionDataSourceProxy` + `@Transactional(readOnly=true)` routing gets reads off the primary.
2. **pgBouncer** for connection multiplexing.
3. **Sharding by `calendar_id`**. Every relevant index already includes `calendar_id` first, and there is no global query that crosses calendars besides aggregate-availability — which is itself naturally batchable per shard.
4. **Outbox + Kafka** for the "meeting created/cancelled" event stream once notifications and audit are in scope.

---

## 6. Why not store slots as raw events / availability rules?

A power-user calendar product would express availability as recurring rules (RRULE-style) and materialize slots on demand. We chose explicit materialized slots because:

- The challenge says "users can create available time slots". Materialization fits that wording directly.
- Recurrence rules carry their own large design surface (timezone DST handling, exception dates, expansion windows) that would push other things off the page.
- The GIST exclusion approach is only meaningful on materialized rows. Going rule-based would push the no-overlap invariant back into the application layer.

This is the single biggest assumption baked into the data model. A future migration to a rules-based model would mostly leave the API the same — slots would just be generated by a service rather than stored — but the underlying tables would change.

---

## 7. Calendar as a domain concept

The challenge says: *"Calendar as the term in the task should be present only in the domain in the service."* We respect that strictly:

- `Calendar` is a JPA entity. It exists in `com.minidoodle.domain`.
- No request DTO or response DTO references it.
- No controller path mentions it.
- The user owns the calendar (1:1), and we create it eagerly when the user is created — the consumer of the API simply talks about "a user's slots".

The benefit of having the entity at all is that the per-user no-overlap invariant is naturally scoped to a stable foreign key (`calendar_id`), which is what the GIST exclusion constraint needs.

---

## 8. Error handling

A single `GlobalExceptionHandler` translates each domain exception to a standard `ErrorResponse` (timestamp, status, error, message, path, optional field errors). Specifically:

| Exception                                  | HTTP   |
|-------------------------------------------|--------|
| `ResourceNotFoundException`                | 404    |
| `InvalidSlotException`, validation errors  | 400    |
| `SlotOverlapException`, `ConflictException`, `DuplicateResourceException` | 409 |
| `OptimisticLockingFailureException`        | 409    |
| `DataIntegrityViolationException` (caught GIST/unique violations) | 409 |
| anything else                              | 500    |

Validation field errors include the offending property name so clients can show inline form errors without parsing free text.

---

## 9. What is deliberately not in the build

- **Authentication**. The exercise gives no indication of an auth context, so endpoints accept any caller. The user model is ready for an `owner_id` audit field; `AuditorAware` is wired in as a no-op stub.
- **Rate limiting**. At this scale, Spring's `RateLimiterRegistry` or a sidecar like Envoy would be the move — out of scope for the exercise.
- **Notifications, ICS, RSVP updates**. The `MeetingParticipant.responseStatus` is in the schema and entity, but there is no endpoint for participants to change it. Adding one is a 30-line patch — left out to keep the surface small.
- **Soft delete / audit log**. We hard-delete. A real production system would soft-delete meetings and emit audit events; the schema would gain `deleted_at` and a separate `meeting_audit` table.
- **OpenTelemetry**. The log pattern reserves slots for `traceId`/`spanId` and Micrometer's tracing bridge can be enabled by adding the Boot starter — left as a single dependency add.

---

## 10. Summary of the headline trade-offs

| Decision                              | What we gain                                | What we give up                                  |
|---------------------------------------|---------------------------------------------|--------------------------------------------------|
| Postgres `EXCLUDE` for overlap        | Bulletproof invariant under any concurrency | Portability away from Postgres                   |
| Materialized slots (not rules)         | Simpler model, DB-enforced correctness      | Cannot natively express recurrence — would need a generation layer |
| Mixed optimistic + pessimistic locking | Right tool for each case                    | Two locking models to keep in mind               |
| Blanket cache eviction on writes       | Simple correctness                          | Inefficient under bursty writes                  |
| One synchronous service (no events)   | Lower operational surface                   | No clean extension point for downstream (notifications, etc.) |
| Hard delete                            | Smaller schema                              | No audit trail for compliance                    |
| No auth                                | Smaller surface for the exercise            | Not production-ready as-is                       |
