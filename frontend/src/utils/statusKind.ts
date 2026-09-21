import type { IncidentActivityEntry, IncidentStatus } from '../types/incident';

/** The five-way color vocabulary the badge/dot styling is built on (see
 * components/badges.module.css) -- shared here so status badges and the activity timeline agree
 * on what each status/event means, rather than each guessing independently. */
export type Kind = 'crit' | 'risk' | 'ok' | 'info' | 'neutral';

export const STATUS_KIND: Record<IncidentStatus, Kind> = {
  OPEN: 'info',
  TRIAGED: 'info',
  INVESTIGATING: 'info',
  MITIGATED: 'risk',
  RESOLVED: 'ok',
  CANCELLED: 'neutral',
};

/** Event types whose color doesn't come from the status they moved to. */
const EVENT_KIND: Partial<Record<IncidentActivityEntry['eventType'], Kind>> = {
  CREATED: 'crit',
  ESCALATED: 'crit',
  PRIORITY_CHANGED: 'crit',
  SEVERITY_CHANGED: 'crit',
  SLA_BREACHED: 'crit',
  ASSIGNED: 'info',
  REASSIGNED: 'info',
  TRIAGED: 'info',
  SERVICE_LINKED: 'info',
  TITLE_CHANGED: 'info',
  DESCRIPTION_CHANGED: 'info',
  SERVICE_UPDATED: 'info',
  RESOLUTION_NOTE_ADDED: 'ok',
  SERVICE_CREATED: 'ok',
  SERVICE_ARCHIVED: 'neutral',
};

/**
 * Which color category an activity-timeline dot should use. Mirrors the approved prototype's
 * per-event color coding (urgency signals red, mitigation/status-progress amber or whatever the
 * resulting status means, routine/administrative blue) instead of a single flat color for every
 * entry. A status change reuses the color of the status it moved *to*, so "changed to Mitigated"
 * and the Mitigated badge elsewhere on the page always agree.
 */
export function activityEventKind(entry: IncidentActivityEntry): Kind {
  if (entry.eventType === 'STATUS_CHANGED' && entry.toValue !== null) {
    const kind = STATUS_KIND[entry.toValue as IncidentStatus];
    if (kind) return kind;
  }
  return EVENT_KIND[entry.eventType] ?? 'info';
}
