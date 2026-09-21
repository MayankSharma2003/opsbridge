/**
 * Locale-aware relative time ("5m ago" / "5分前") via the built-in Intl.RelativeTimeFormat --
 * no hand-rolled unit suffixes, no new dependency. `locale` defaults to 'en'; callers pass
 * i18next's current language so this actually follows the UI language, not just the theme.
 */
export function formatRelativeTime(iso: string, now: Date = new Date(), locale = 'en'): string {
  const diffSeconds = Math.round((new Date(iso).getTime() - now.getTime()) / 1000);
  const rtf = new Intl.RelativeTimeFormat(locale, { numeric: 'auto', style: 'narrow' });

  const absSeconds = Math.abs(diffSeconds);
  if (absSeconds < 60) return rtf.format(diffSeconds, 'second');
  const diffMinutes = Math.round(diffSeconds / 60);
  if (Math.abs(diffMinutes) < 60) return rtf.format(diffMinutes, 'minute');
  const diffHours = Math.round(diffMinutes / 60);
  if (Math.abs(diffHours) < 24) return rtf.format(diffHours, 'hour');
  const diffDays = Math.round(diffHours / 24);
  return rtf.format(diffDays, 'day');
}

/**
 * Locale-aware short weekday name for a plain "YYYY-MM-DD" date (the dashboard's MTTR trend
 * points) -- forced to UTC so parsing "2026-03-08" doesn't shift a day in negative-offset zones.
 */
export function formatWeekday(dateOnly: string, locale = 'en'): string {
  return new Intl.DateTimeFormat(locale, { weekday: 'short', timeZone: 'UTC' }).format(new Date(dateOnly));
}

/** "HH:MM:SS" countdown to a target instant, or null once the target has passed. */
export function formatCountdown(targetIso: string | null, now: Date = new Date()): string | null {
  if (!targetIso) return null;
  const remainingSeconds = Math.floor((new Date(targetIso).getTime() - now.getTime()) / 1000);
  if (remainingSeconds <= 0) return null;
  const hours = Math.floor(remainingSeconds / 3600);
  const minutes = Math.floor((remainingSeconds % 3600) / 60);
  const seconds = remainingSeconds % 60;
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${pad(hours)}:${pad(minutes)}:${pad(seconds)}`;
}

/**
 * The earliest SLA target the incident hasn't yet hit -- response, then mitigation, then
 * resolution, skipping any milestone already achieved. Mirrors SlaCalculator.nextUnmetTarget for
 * display purposes only; the backend's sla.state remains the authoritative breach signal.
 */
export function nextOutstandingTarget(sla: {
  respondedAt: string | null;
  responseTargetAt: string | null;
  mitigatedAt: string | null;
  mitigationTargetAt: string | null;
  resolvedAt: string | null;
  resolutionTargetAt: string | null;
}): string | null {
  const candidates = [
    sla.respondedAt === null ? sla.responseTargetAt : null,
    sla.mitigatedAt === null ? sla.mitigationTargetAt : null,
    sla.resolvedAt === null ? sla.resolutionTargetAt : null,
  ].filter((value): value is string => value !== null);
  if (candidates.length === 0) return null;
  return candidates.reduce((earliest, current) => (current < earliest ? current : earliest));
}

/**
 * How far elapsed from `startedAt` to `targetIso`, as a 0-100 percentage -- what the SLA
 * Remaining bar's fill represents. Clamped so a target already passed doesn't overflow the bar,
 * and a missing start or zero-length window reads as fully elapsed rather than dividing by zero.
 */
export function slaProgressPercent(startedAt: string | null, targetIso: string, now: Date = new Date()): number {
  if (!startedAt) return 100;
  const start = new Date(startedAt).getTime();
  const target = new Date(targetIso).getTime();
  const total = target - start;
  if (total <= 0) return 100;
  const elapsed = now.getTime() - start;
  return Math.min(100, Math.max(0, (elapsed / total) * 100));
}
