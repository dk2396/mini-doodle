package com.minidoodle.domain;

public enum SlotStatus {
    /** Slot is open and can be booked into a meeting. */
    FREE,
    /** Slot is occupied (e.g. has a meeting, or marked unavailable). */
    BUSY
}
