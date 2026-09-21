import { describe, expect, it } from 'vitest';
import '../../i18n';
import i18next from '../../i18n';
import { formatActivityText } from '../../utils/formatActivity';
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

describe('formatActivityText', () => {
  const t = i18next.getFixedT('en');

  it('renders a declared-incident sentence for CREATED', () => {
    expect(formatActivityText(entry({ eventType: 'CREATED' }), t)).toBe('R. Villanueva declared this incident');
  });

  it('translates raw status enum values through the status namespace', () => {
    const text = formatActivityText(
      entry({ eventType: 'STATUS_CHANGED', fromValue: 'OPEN', toValue: 'TRIAGED' }),
      t,
    );
    expect(text).toBe('R. Villanueva changed status from Open to Triaged');
  });

  it('translates raw priority values through the priority namespace', () => {
    const text = formatActivityText(
      entry({ eventType: 'PRIORITY_CHANGED', fromValue: 'P2', toValue: 'P1' }),
      t,
    );
    expect(text).toBe('R. Villanueva changed priority from P2 to P1');
  });

  it('shows assignee emails as-is, untranslated', () => {
    const text = formatActivityText(
      entry({ eventType: 'ASSIGNED', fromValue: null, toValue: 'operator@opsbridge.local' }),
      t,
    );
    expect(text).toBe('R. Villanueva assigned to operator@opsbridge.local');
  });

  it('renders assigning-to-nobody as an unassign sentence, not "assigned to Unassigned"', () => {
    const text = formatActivityText(
      entry({ eventType: 'REASSIGNED', fromValue: 'operator@opsbridge.local', toValue: null }),
      t,
    );
    expect(text).toBe('R. Villanueva unassigned operator@opsbridge.local');
  });

  it('falls back to a generic sentence for an eventType with no template', () => {
    const text = formatActivityText(
      entry({ eventType: 'SOME_FUTURE_TYPE' as IncidentActivityEntry['eventType'] }),
      t,
    );
    expect(text).toBe('R. Villanueva performed an action');
  });
});
