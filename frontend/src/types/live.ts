import type { IncidentSummary } from './incident';

export type LiveEventType = 'INCIDENT_UPDATED' | 'SLA_BREACHED';

export interface LiveEvent {
  type: LiveEventType;
  occurredAt: string;
  incident: IncidentSummary;
}

// UI-facing channel state: offline is terminal-ish (logged out or gave up); reconnecting means a retry is already scheduled.
export type LiveStatus = 'idle' | 'connecting' | 'live' | 'reconnecting' | 'offline';
