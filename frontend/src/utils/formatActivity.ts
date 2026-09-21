import type { IncidentActivityEntry } from '../types/incident';

/** Loosely typed on purpose: react-i18next's TFunction generics are awkward to accept as a param type. */
type Translate = (key: string, options?: Record<string, unknown>) => string;

/** Which i18n namespace, if any, an event's raw from/to values should be looked up through. */
const VALUE_NAMESPACE: Partial<Record<IncidentActivityEntry['eventType'], string>> = {
  STATUS_CHANGED: 'status',
  PRIORITY_CHANGED: 'priority',
  SEVERITY_CHANGED: 'severity',
};

/**
 * Renders one activity-log row as a sentence. eventType/fieldName/fromValue/toValue arrive raw
 * from the backend (API convention: enums are never pre-localized server-side) -- this is where
 * that translation happens, once, rather than in every place a timeline might be shown.
 */
export function formatActivityText(entry: IncidentActivityEntry, t: Translate): string {
  const actor = entry.actor.name;

  // Assigning/reassigning to no one is really an unassign -- its own sentence reads better than
  // "assigned to Unassigned".
  if ((entry.eventType === 'ASSIGNED' || entry.eventType === 'REASSIGNED') && entry.toValue === null) {
    return t('pages.activity.UNASSIGNED', { actor, from: entry.fromValue ?? t('common.unassigned') });
  }

  const namespace = VALUE_NAMESPACE[entry.eventType];
  const translateValue = (value: string | null): string => {
    if (value === null) return t('common.unassigned');
    return namespace ? t(`${namespace}.${value}`, { defaultValue: value }) : value;
  };

  const params = { actor, from: translateValue(entry.fromValue), to: translateValue(entry.toValue) };
  return t(`pages.activity.${entry.eventType}`, { ...params, defaultValue: t('pages.activity.fallback', params) });
}
