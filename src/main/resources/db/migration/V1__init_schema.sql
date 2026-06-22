-- =====================================================================
-- V1: Initial schema for Mini Doodle
--
-- Design notes:
-- * "calendar" lives in the domain. Each user has exactly one calendar
--   (created lazily / on user creation). Calendar is not exposed via API.
-- * Slots use [start_time, end_time) half-open ranges (start inclusive,
--   end exclusive) - the standard convention for time ranges.
-- * tstzrange + GIST exclusion constraint guarantees NO overlapping
--   slots within the same calendar at the database level. This is the
--   strongest correctness guarantee we can give and removes the need
--   for SELECT-then-INSERT race conditions.
-- * Meetings are 1:1 with slots (a slot becomes a meeting when booked).
--   Participants are stored separately for normalized querying.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ---------- users ----------
CREATE TABLE users (
    id              BIGSERIAL PRIMARY KEY,
    email           VARCHAR(254) NOT NULL UNIQUE,
    display_name    VARCHAR(120) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         BIGINT      NOT NULL DEFAULT 0
);

CREATE INDEX idx_users_email ON users (email);

-- ---------- calendars (domain only - one per user) ----------
CREATE TABLE calendars (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT      NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    timezone        VARCHAR(64) NOT NULL DEFAULT 'UTC',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         BIGINT      NOT NULL DEFAULT 0
);

-- ---------- slots ----------
CREATE TABLE slots (
    id              BIGSERIAL PRIMARY KEY,
    calendar_id     BIGINT      NOT NULL REFERENCES calendars(id) ON DELETE CASCADE,
    start_time      TIMESTAMPTZ NOT NULL,
    end_time        TIMESTAMPTZ NOT NULL,
    status          VARCHAR(16) NOT NULL,        -- FREE, BUSY
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT chk_slot_times CHECK (end_time > start_time),
    CONSTRAINT chk_slot_status CHECK (status IN ('FREE','BUSY')),

    -- Prevent overlapping slots in the same calendar at the DB level.
    -- Two slots in the same calendar with overlapping [start, end) ranges
    -- cannot coexist.
    CONSTRAINT slot_no_overlap EXCLUDE USING gist (
        calendar_id WITH =,
        tstzrange(start_time, end_time, '[)') WITH &&
    )
);

CREATE INDEX idx_slots_calendar_time   ON slots (calendar_id, start_time, end_time);
CREATE INDEX idx_slots_status          ON slots (status);
CREATE INDEX idx_slots_time_range_gist ON slots USING gist (tstzrange(start_time, end_time, '[)'));

-- ---------- meetings ----------
CREATE TABLE meetings (
    id              BIGSERIAL PRIMARY KEY,
    slot_id         BIGINT      NOT NULL UNIQUE REFERENCES slots(id) ON DELETE CASCADE,
    organizer_id    BIGINT      NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    title           VARCHAR(200) NOT NULL,
    description     TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         BIGINT      NOT NULL DEFAULT 0
);

CREATE INDEX idx_meetings_organizer ON meetings (organizer_id);

-- ---------- meeting_participants ----------
CREATE TABLE meeting_participants (
    id              BIGSERIAL PRIMARY KEY,
    meeting_id      BIGINT      NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
    user_id         BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    response_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_meeting_participant UNIQUE (meeting_id, user_id),
    CONSTRAINT chk_response_status CHECK (response_status IN ('PENDING','ACCEPTED','DECLINED'))
);

CREATE INDEX idx_participants_user ON meeting_participants (user_id);
