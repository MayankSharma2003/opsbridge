import { describe, expect, it } from 'vitest';
import { formatMinutes } from '../../utils/formatMinutes';

const t = (key: string, options?: Record<string, unknown>) => {
  const count = options?.count as number;
  if (key === 'common.minutesShort') return `${count}m`;
  if (key === 'common.hoursShort') return `${count}h`;
  return key;
};

describe('formatMinutes', () => {
  it('renders sub-hour durations as minutes only', () => {
    expect(formatMinutes(41, t)).toBe('41m');
  });

  it('renders multi-hour durations as hours and minutes', () => {
    expect(formatMinutes(90, t)).toBe('1h 30m');
  });

  it('renders an exact hour with a 0m remainder', () => {
    expect(formatMinutes(120, t)).toBe('2h 0m');
  });

  it('rounds fractional minutes before splitting into hours/minutes', () => {
    expect(formatMinutes(89.6, t)).toBe('1h 30m');
  });
});
