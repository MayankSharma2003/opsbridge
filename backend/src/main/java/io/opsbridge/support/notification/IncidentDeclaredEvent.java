package io.opsbridge.support.notification;

import java.util.UUID;

// Sibling of IncidentChangedEvent, not a reuse -- that event means "status or assignment moved"
// with no discriminator, so a listener can't tell a fresh declaration from the tenth status
// change. Same mechanism (publish an id, re-read after commit), different fact.
public record IncidentDeclaredEvent(UUID incidentId) {
}
