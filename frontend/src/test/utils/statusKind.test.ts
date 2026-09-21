import { describe, expect, it } from 'vitest';
import { activityEventKind, STATUS_KIND } from '../../utils/statusKind';
import type { IncidentActivityEntry } from '../../types/incident';

const ACTOR = { id: 'u1', name: 'R. Villanueva', email: 'r@opsbridge.local', role: 'OPERATOR' as const };

function entry(overrides: Partial<IncidentActivityEntry>): IncidentActivityEntry {
  return {
    id: 'a1',
    actor: ACTOR,
    eventType: 'CREATED',
    fieldName: null,
    fromValue: null,
    toValue: null,
    comment: null,
    createdAt: '2026-03-01T00:00:00Z',
    ...overrides,
  };
}

describe('activityEventKind', () => {
  it('colors a declared incident as urgent', () => {
    expect(activityEventKind(entry({ eventType: 'CREATED' }))).toBe('crit');
  });

  it('colors an SLA breach as urgent', () => {
    expect(activityEventKind(entry({ eventType: 'SLA_BREACHED' }))).toBe('crit');
  });

  it('colors a severity increase as urgent', () => {
    expect(activityEventKind(entry({ eventType: 'SEVERITY_CHANGED', fromValue: 'SEV2', toValue: 'SEV1' }))).toBe(
      'crit',
    );
  });

  it('colors an assignment as routine', () => {
    expect(activityEventKind(entry({ eventType: 'ASSIGNED', toValue: 'operator@opsbridge.local' }))).toBe('info');
  });

  it('colors a resolution note as positive', () => {
    expect(activityEventKind(entry({ eventType: 'RESOLUTION_NOTE_ADDED' }))).toBe('ok');
  });

  it('colors a service archival as neutral', () => {
    expect(activityEventKind(entry({ eventType: 'SERVICE_ARCHIVED' }))).toBe('neutral');
  });

  it('reuses the target status color for a status change, so the dot always agrees with the badge it produced', () => {
    expect(activityEventKind(entry({ eventType: 'STATUS_CHANGED', toValue: 'RESOLVED' }))).toBe(STATUS_KIND.RESOLVED);
    expect(activityEventKind(entry({ eventType: 'STATUS_CHANGED', toValue: 'MITIGATED' }))).toBe(
      STATUS_KIND.MITIGATED,
    );
    expect(activityEventKind(entry({ eventType: 'STATUS_CHANGED', toValue: 'CANCELLED' }))).toBe(
      STATUS_KIND.CANCELLED,
    );
  });

  it('falls back to info for a status change with no resolvable target', () => {
    expect(activityEventKind(entry({ eventType: 'STATUS_CHANGED', toValue: null }))).toBe('info');
  });

  it('falls back to info for an event type with no explicit mapping', () => {
    expect(activityEventKind(entry({ eventType: 'SOME_FUTURE_TYPE' as IncidentActivityEntry['eventType'] }))).toBe(
      'info',
    );
  });
});
