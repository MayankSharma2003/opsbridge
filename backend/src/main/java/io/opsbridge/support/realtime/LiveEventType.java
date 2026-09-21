package io.opsbridge.support.realtime;

public enum LiveEventType {
    /** An incident's status or assignee changed. */
    INCIDENT_UPDATED,
    // Crossed into BREACHED because a deadline passed with no one writing to it.
    SLA_BREACHED
}
