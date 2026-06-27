ALTER TABLE meetings DROP COLUMN slot_id;

ALTER TABLE slots
    ADD COLUMN meeting_id BIGINT REFERENCES meetings(id) ON DELETE SET NULL;

CREATE INDEX idx_slots_meeting ON slots(meeting_id);

-- A meeting has at most one slot per calendar. The service layer only
-- ever inserts one mirror slot per attendee per meeting; this constraint
-- catches accidental duplicates at the DB level.
ALTER TABLE slots
    ADD CONSTRAINT uq_meeting_per_calendar UNIQUE (meeting_id, calendar_id);
