package io.opsbridge.service;

import io.opsbridge.domain.entity.Service;

// Aggregates over incidents, not columns on the service -- paired here so the controller maps one
// object to one response without a second lookup.
// urgentOpenIncidentCount is the P1/P2 subset of openIncidentCount; frontend decides labels, raw
// counts cross the wire.
public record ServiceWithOpenIncidents(Service service, long openIncidentCount, long urgentOpenIncidentCount) {
}
