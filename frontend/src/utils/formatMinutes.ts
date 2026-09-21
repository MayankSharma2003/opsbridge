type Translate = (key: string, options?: Record<string, unknown>) => string;

/**
 * Renders a minute count as a short duration ("41m", "1h 30m"). Routed through i18n like every
 * other unit suffix in this app -- "h"/"m" read as English shorthand, and Japanese has its own
 * native duration units, not a borrowed abbreviation.
 */
export function formatMinutes(totalMinutes: number, t: Translate): string {
  const rounded = Math.round(totalMinutes);
  const hours = Math.floor(rounded / 60);
  const minutes = rounded % 60;
  if (hours === 0) {
    return t('common.minutesShort', { count: minutes });
  }
  return `${t('common.hoursShort', { count: hours })} ${t('common.minutesShort', { count: minutes })}`;
}
