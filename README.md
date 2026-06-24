# Mini Doodle

A high-performance meeting scheduling platform built with **Spring Boot 3.3**, **Java 21**, **PostgreSQL 16**, and **Redis 7**. Users manage time slots in a personal calendar, convert slots into meetings with participants, and query free/busy availability across users.

> **Calendar is a domain concept only** — it is created lazily for each user on signup and is never exposed via the API, exactly as specified in the challenge.

---

## Table of Contents

1. [Quick Start](#quick-start)
2. [Architecture](#architecture)
3. [API Reference](#api-reference)
4. [Configuration](#configuration)
5. [Testing](#testing)
6. [Observability](#observability)
7. [Project Structure](#project-structure)
8. [See Also](#see-also)

---

## Quick Start

### Prerequisites
- Docker + Docker Compose
- (for local builds) JDK 21, Maven 3.9+

### Run with docker-compose

```bash
docker compose up --build
```

This starts:
- **postgres** on `:5432` (db/user/pass: `minidoodle`)
- **redis** on `:6379`
- **app** on `:8080`

The app waits for Postgres and Redis to report healthy, runs Flyway migrations on startup, then begins serving.

### Verify it's up

```bash
curl http://localhost:8080/actuator/health
# → {"status":"UP",...}
```

### Open the API docs

- Swagger UI: <http://localhost:8080/swagger-ui.html>
- OpenAPI JSON: <http://localhost:8080/v3/api-docs>

### Stop

```bash
docker compose down          # keep data
docker compose down -v       # nuke the postgres volume too
```

---

## Architecture

```
┌──────────────────┐    ┌──────────────────┐    ┌──────────────────┐
│  REST Controllers│ →  │     Services     │ →  │   Repositories   │
│   (validation,   │    │  (business +     │    │  (Spring Data    │
│    serialization)│    │   transactions)  │    │   JPA queries)   │
└──────────────────┘    └──────────────────┘    └──────────────────┘
                              │                          │
                              ↓                          ↓
                       ┌──────────────┐         ┌────────────────┐
                       │  Redis Cache │         │  PostgreSQL 16 │
                       │  (read-heavy │         │  (Flyway, GIST │
                       │   endpoints) │         │   exclusion)   │
                       └──────────────┘         └────────────────┘
```

**Domain model**

| Entity              | Relationship                                                  |
|---------------------|---------------------------------------------------------------|
| `User`              | 1 ↔ 1 `Calendar`                                              |
| `Calendar`          | 1 ↔ N `Slot` (the unit-of-work for the no-overlap invariant) |
| `Slot`              | 0 ↔ 1 `Meeting` (a slot becomes a meeting when booked)        |
| `Meeting`           | 1 ↔ N `MeetingParticipant` ↔ 1 `User`                         |

**Key design decisions** are documented in detail in [`DESIGN.md`](./DESIGN.md). In short:

- **Database-enforced correctness.** A PostgreSQL `EXCLUDE` constraint on `tstzrange(start_time, end_time, '[)')` per calendar makes it impossible to insert overlapping slots — race conditions cannot violate the invariant.
- **Half-open `[start, end)` ranges** — adjacent slots like `09:00–10:00` and `10:00–11:00` don't conflict.
- **Pessimistic locking** on the slot row during booking + the `slot_id` unique key on `meetings` makes double-booking impossible.
- **Optimistic locking (`@Version`)** elsewhere keeps lost-update bugs out of the hot path without holding row locks.
- **Redis cache** on the aggregate-availability endpoint with short TTL and cross-mutation eviction.
- **Hibernate batch inserts** (`batch_size=50`) for bulk slot creation; one round trip per batch.

---

## API Reference

All endpoints return JSON. Times are ISO-8601 instants in UTC (e.g. `2025-01-15T14:00:00Z`).

### Users

| Method | Path                    | Description                              |
|--------|-------------------------|------------------------------------------|
| POST   | `/api/v1/users`         | Create a user (calendar is created lazily) |
| GET    | `/api/v1/users/{id}`    | Get a user                               |
| DELETE | `/api/v1/users/{id}`    | Delete user and all owned data           |

```bash
curl -X POST http://localhost:8080/api/v1/users \
  -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com","displayName":"Alice","timezone":"Europe/Berlin"}'
```

Response `201`:
```json
{"id":1,"email":"alice@example.com","displayName":"Alice","timezone":"Europe/Berlin","createdAt":"2025-01-15T13:00:00Z"}
```

### Slots

| Method | Path                                              | Description                       |
|--------|---------------------------------------------------|-----------------------------------|
| POST   | `/api/v1/users/{userId}/slots`                    | Create a single slot              |
| POST   | `/api/v1/users/{userId}/slots/bulk`               | Create up to 500 slots in one call|
| GET    | `/api/v1/users/{userId}/slots?from=&to=&status=`  | List slots in range (paginated)   |
| GET    | `/api/v1/slots/{slotId}`                          | Get a slot                        |
| PATCH  | `/api/v1/slots/{slotId}`                          | Partial update (booked slots rejected) |
| DELETE | `/api/v1/slots/{slotId}`                          | Delete (booked slots rejected)    |

```bash
# Create a slot
curl -X POST http://localhost:8080/api/v1/users/1/slots \
  -H 'Content-Type: application/json' \
  -d '{"startTime":"2025-01-20T09:00:00Z","endTime":"2025-01-20T09:30:00Z"}'

# Bulk-create slots
curl -X POST http://localhost:8080/api/v1/users/1/slots/bulk \
  -H 'Content-Type: application/json' \
  -d '{"slots":[
    {"startTime":"2025-01-20T09:00:00Z","endTime":"2025-01-20T09:30:00Z"},
    {"startTime":"2025-01-20T09:30:00Z","endTime":"2025-01-20T10:00:00Z"}
  ]}'

# List
curl 'http://localhost:8080/api/v1/users/1/slots?from=2025-01-20T00:00:00Z&to=2025-01-21T00:00:00Z&status=FREE&page=0&size=50&sort=startTime'
```

**Validation rules** (configurable in `application.yml` under `app.slots`):
- `startTime` strictly before `endTime` (half-open `[start, end)`)
- Both must be aligned to the minute (no sub-minute precision)
- Duration ∈ `[5, 480]` minutes
- `endTime` within `365` days from now
- No overlap with existing slots in the same calendar (enforced at the DB)

**Error responses** are uniform:
```json
{
  "timestamp":"2025-01-15T14:00:00Z",
  "status":409,
  "error":"Conflict",
  "message":"Slot overlaps an existing slot in the same calendar.",
  "path":"/api/v1/users/1/slots"
}
```

### Meetings

| Method | Path                                  | Description                          |
|--------|---------------------------------------|--------------------------------------|
| POST   | `/api/v1/slots/{slotId}/meetings`     | Convert a FREE slot into a meeting   |
| GET    | `/api/v1/meetings/{meetingId}`        | Get a meeting with participants      |
| DELETE | `/api/v1/meetings/{meetingId}`        | Cancel — frees the underlying slot   |

```bash
curl -X POST http://localhost:8080/api/v1/slots/42/meetings \
  -H 'Content-Type: application/json' \
  -d '{
    "title":"Quarterly planning",
    "description":"Q1 OKRs",
    "organizerId":1,
    "participantUserIds":[2,3,4]
  }'
```

The organizer is auto-added to `participants` if not in the list. The slot becomes `BUSY` and gets a back-reference to the meeting atomically.

### Availability (aggregate view)

```bash
curl -X POST http://localhost:8080/api/v1/availability/aggregate \
  -H 'Content-Type: application/json' \
  -d '{
    "userIds":[1,2,3],
    "from":"2025-01-20T00:00:00Z",
    "to":"2025-01-20T23:59:59Z",
    "status":"FREE"
  }'
```

Response groups slots by user id; users with no matching slots appear with an empty list. Cached in Redis for `app.aggregate.cache-ttl-seconds` (default 30s); writes invalidate the cache.

---

## Configuration

Configurable via env vars (docker) or `application.yml`:

| Property                              | Default | Meaning                                   |
|---------------------------------------|---------|-------------------------------------------|
| `app.slots.min-duration-minutes`      | 5       | Minimum slot length                       |
| `app.slots.max-duration-minutes`      | 480     | Maximum slot length                       |
| `app.slots.max-future-days`           | 365     | How far in the future slots can be placed |
| `app.slots.max-page-size`             | 200     | Max page size on list endpoints           |
| `app.aggregate.cache-ttl-seconds`     | 30      | Aggregate cache TTL                       |
| `spring.datasource.hikari.maximum-pool-size` | 20 | DB connection pool size                |

---

## Testing

```bash
# Run all tests (requires Docker for Testcontainers)
./mvnw test

# Or in the container build (skipped by default to keep image lean):
mvn test
```

What's covered:

- **Unit tests** (`src/test/java/com/minidoodle/service/`):
  - `SlotServiceTest` — validation, overlap pre-check, partial update rules, deletion guards.
  - `MeetingServiceTest` — booking lifecycle, auto-add organizer, missing-user rejection.
  - `AvailabilityServiceTest` — range validation, grouping, empty-user preservation.
  - `UserServiceTest` — email normalization, duplicates, missing.
- **Integration tests** (`src/test/java/com/minidoodle/integration/`):
  - `EndToEndIntegrationTest` — full HTTP flow on a real Postgres via Testcontainers.
  - `ConcurrencyIntegrationTest` — N threads hammering the same slot; verifies exactly one booking wins and exactly one of N overlapping slot creations survives the GIST constraint.
- **Repository slice** (`SlotRepositoryIntegrationTest`) — range query and overlap predicates against real Postgres.

---

## Observability

- **Health**: `GET /actuator/health` (with liveness/readiness probes for k8s)
- **Metrics**: `GET /actuator/prometheus` (Micrometer + Prometheus registry)
  - HTTP request histograms with percentiles enabled
  - `@Timed`-annotated hot paths: `minidoodle.slot.create`, `minidoodle.slot.update`, `minidoodle.slot.list`, `minidoodle.meeting.create`, `minidoodle.meeting.cancel`, `minidoodle.availability.aggregate`
- **Structured logs**: console with traceId/spanId placeholders ready for OTel/Sleuth wiring.

---

## Project Structure

```
mini-doodle/
├── docker-compose.yml         # postgres + redis + app
├── Dockerfile                 # multi-stage build, non-root user
├── pom.xml
├── README.md                  # this file
├── DESIGN.md                  # trade-offs + design decisions
└── src/
    ├── main/
    │   ├── java/com/minidoodle/
    │   │   ├── MiniDoodleApplication.java
    │   │   ├── config/         # OpenAPI, cache, JPA, properties binding
    │   │   ├── controller/     # REST adapters
    │   │   ├── domain/         # JPA entities
    │   │   ├── dto/            # Request/response records
    │   │   ├── exception/      # Domain exceptions + global handler
    │   │   ├── mapper/         # MapStruct domain ↔ DTO
    │   │   ├── repository/     # Spring Data interfaces
    │   │   └── service/        # Business logic + transactions
    │   └── resources/
    │       ├── application.yml
    │       ├── application-docker.yml
    │       └── db/migration/V1__init_schema.sql
    └── test/
        ├── java/com/minidoodle/
        │   ├── integration/    # Testcontainers e2e + concurrency
        │   ├── repository/     # JPA queries
        │   └── service/        # Mockito-based unit tests
        └── resources/application-test.yml
```

---

## See Also

- [`DESIGN.md`](./DESIGN.md) — trade-offs, scaling path, alternatives considered, what was deliberately deferred.
