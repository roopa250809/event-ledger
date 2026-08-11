package com.eventledger.gateway.domain;

/** Describes an event's current processing lifecycle state. */
public enum ProcessingStatus {
    PENDING,
    QUEUED,
    APPLIED,
    FAILED
}
