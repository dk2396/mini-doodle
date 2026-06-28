# Mini Doodle

Meeting scheduling platform built with Spring Boot 3.3, Java 21, PostgreSQL 16, and Redis 7. Users advertise time slots, slots are converted into meetings with multiple participants, and free/busy availability can be queried per-user or aggregated across users.

Design rationale and trade-offs live in [`DESIGN.md`](./DESIGN.md).

---

## Run locally

Prerequisites: Docker + Docker Compose. (For non-containerised builds: JDK 21, Maven 3.9+.)

### With docker-compose (default)

```bash
docker compose up --build
```

This starts three containers:
- `postgres` on `:5432` (db / user / pass: `minidoodle`)
- `redis` on `:6379`
- `app` on `:8080`

The app waits for both dependencies to report healthy, then runs Flyway migrations and serves.

Health check:
```bash
curl http://localhost:8080/actuator/health
```

API docs:
- Swagger UI — http://localhost:8080/swagger-ui.html
- OpenAPI JSON — http://localhost:8080/v3/api-docs

Stop:
```bash
docker compose down       # keep data
docker compose down -v    # nuke volumes
```

### App on host, deps in containers (for debugging)

Useful if you want to attach a debugger or iterate on the JVM without rebuilding the image:

```bash
docker compose up -d postgres redis     # start only the dependencies
mvn spring-boot:run                     # run the app on the host
```

`application.yml` defaults point at `localhost:5432`/`localhost:6379`, so no override is needed. Don't set `SPRING_PROFILES_ACTIVE=docker` on the host — that profile uses container DNS names that won't resolve outside the compose network.

---

## How to consume the API

All endpoints return JSON. Times are ISO-8601 instants in UTC.

### Users

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/users` | Create a user (calendar is created lazily) |
| GET | `/api/v1/users/{id}` | Get a user |
| DELETE | `/api/v1/users/{id}` | Delete user and all owned data |

```bash
curl -X POST http://localhost:8080/api/v1/users \
  -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com","displayName":"Alice","timezone":"Europe/Berlin"}'
```

### Slots

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/users/{userId}/slots` | Create a slot |
| POST | `/api/v1/users/{userId}/slots/bulk` | Create up to 500 slots in one call |
| GET | `/api/v1/users/{userId}/slots?from=&to=&status=` | List slots in range (paginated) |
| GET | `/api/v1/slots/{slotId}` | Get a slot |
| PATCH | `/api/v1/slots/{slotId}` | Partial update (booked slots rejected) |
| DELETE | `/api/v1/slots/{slotId}` | Delete (booked slots rejected) |

```bash
# Create a slot
curl -X POST http://localhost:8080/api/v1/users/1/slots \
  -H 'Content-Type: application/json' \
  -d '{"startTime":"2026-07-01T09:00:00Z","endTime":"2026-07-01T09:30:00Z"}'

# List
curl 'http://localhost:8080/api/v1/users/1/slots?from=2026-07-01T00:00:00Z&to=2026-07-02T00:00:00Z&status=FREE'
```

**Validation:**
- `startTime` strictly before `endTime`, both aligned to the minute
- Duration ∈ [5, 480] minutes
- `endTime` within 365 days from now
- No overlap with existing slots in the same calendar (enforced at the DB)

### Meetings

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/slots/{slotId}/meetings` | Convert a FREE slot into a meeting |
| GET | `/api/v1/meetings/{meetingId}` | Get a meeting with attendees |
| DELETE | `/api/v1/meetings/{meetingId}` | Cancel — frees every bound slot |

```bash
curl -X POST http://localhost:8080/api/v1/slots/42/meetings \
  -H 'Content-Type: application/json' \
  -d '{
    "title": "Quarterly planning",
    "description": "Q3 OKRs",
    "organizerId": 1,
    "participantUserIds": [2, 3]
  }'
```

**Booking semantics** (default-unavailable; details in `DESIGN.md` §3):

- The organizer's slot at `slotId` must be FREE.
- Every invited participant must have an **exact-time-match** FREE slot on their own calendar. Without one, the request fails with 409.
- All matching slots (organizer's + invitees') flip FREE → BUSY and bind to the meeting via `slots.meeting_id`.
- Organizer is auto-ACCEPTED in the participant list; invitees start at PENDING.

If an invitee has only partial availability, the 409 message names the windows:

```json
{
  "timestamp": "2026-07-01T14:00:00Z",
  "status": 409,
  "error": "Conflict",
  "message": "User 2 is only partially available in 2026-07-01T09:00:00Z to 2026-07-01T10:00:00Z. Their FREE windows in this range: 2026-07-01T09:00:00Z–2026-07-01T09:30:00Z. Either shorten the meeting to match, or ask the user to advertise the full range.",
  "path": "/api/v1/slots/42/meetings"
}
```

### Availability (aggregate view)

```bash
curl -X POST http://localhost:8080/api/v1/availability/aggregate \
  -H 'Content-Type: application/json' \
  -d '{
    "userIds": [1, 2, 3],
    "from": "2026-07-01T00:00:00Z",
    "to": "2026-07-01T23:59:59Z",
    "status": "FREE"
  }'
```

Response groups slots by user id; users with no matching slots appear with an empty list. Cached in Redis with a short TTL (default 30s); writes invalidate the cache.

---

## Domain model

| Entity | Relationship |
|---|---|
| `User` | 1 ↔ 1 `Calendar` |
| `Calendar` | 1 ↔ N `Slot` (scope of the no-overlap invariant; never exposed via API per the brief) |
| `Slot` | N ↔ 1 `Meeting` (many slots can bind to the same meeting — one per attendee) |
| `Meeting` | 1 ↔ N `MeetingParticipant` ↔ 1 `User` |

Key design points (more in [`DESIGN.md`](./DESIGN.md)):

- A PostgreSQL `EXCLUDE` constraint on `tstzrange(start_time, end_time, '[)')` per calendar makes overlapping slots impossible at the database level.
- Half-open `[start, end)` ranges throughout — `09:00–10:00` and `10:00–11:00` don't conflict.
- Pessimistic `SELECT FOR UPDATE` on the slot row during booking, plus V2's `UNIQUE (meeting_id, calendar_id)` constraint on `slots`, prevents double-booking.
- Optimistic locking (`@Version`) on non-state-transition updates.
- Redis cache fronts aggregate availability with blanket eviction on writes.

---

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `app.slots.min-duration-minutes` | 5 | Minimum slot length |
| `app.slots.max-duration-minutes` | 480 | Maximum slot length |
| `app.slots.max-future-days` | 365 | How far in the future slots can be placed |
| `app.slots.max-page-size` | 200 | Max page size on list endpoints |
| `app.aggregate.cache-ttl-seconds` | 30 | Aggregate cache TTL |
| `spring.datasource.hikari.maximum-pool-size` | 20 | DB connection pool size |

---

## Testing

```bash
mvn test                                # full suite; needs Docker daemon for Testcontainers
mvn test -Dtest='!*IntegrationTest'     # unit tests only, no Docker needed
mvn test -Dtest=SlotServiceTest         # a single class
```

Unit tests (Mockito) sit under `src/test/java/com/minidoodle/service/` — fast, no infrastructure. Integration tests (Testcontainers Postgres) sit under `src/test/java/com/minidoodle/integration/` and cover the full HTTP flow, concurrent booking and overlap protection at the DB level, and repository range queries.

---

## Observability

- `GET /actuator/health` — liveness and readiness probes
- `GET /actuator/prometheus` — Micrometer metrics in Prometheus format
- Structured logs with `traceId` / `spanId` placeholders for OpenTelemetry

---

## Project structure

```
mini-doodle/
├── docker-compose.yml      # postgres + redis + app
├── Dockerfile              # multi-stage build, non-root user
├── pom.xml
├── README.md
├── DESIGN.md
└── src/
    ├── main/
    │   ├── java/com/minidoodle/
    │   │   ├── config/         # OpenAPI, cache, properties binding
    │   │   ├── controller/     # REST adapters
    │   │   ├── domain/         # JPA entities
    │   │   ├── dto/            # Request/response records
    │   │   ├── exception/      # Domain exceptions + global handler
    │   │   ├── mapper/         # MapStruct domain ↔ DTO
    │   │   ├── repository/     # Spring Data interfaces
    │   │   └── service/        # Business logic + transactions
    │   └── resources/
    │       ├── application.yml
    │       └── db/migration/
    │           ├── V1__init_schema.sql       # users, calendars, slots, meetings + GIST exclusion
    │           └── V2__meeting_slots.sql     # slots.meeting_id; a meeting binds N slots
    └── test/
        └── java/com/minidoodle/
            ├── integration/    # Testcontainers e2e + concurrency
            ├── repository/     # JPA queries
            └── service/        # Mockito-based unit tests
```