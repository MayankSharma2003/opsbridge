import { describe, expect, it } from 'vitest';
import {
  formatCountdown,
  formatRelativeTime,
  formatWeekday,
  nextOutstandingTarget,
  slaProgressPercent,
} from '../../utils/time';

const NOW = new Date('2026-03-01T12:00:00Z');

describe('formatRelativeTime', () => {
  it('formats sub-minute durations in seconds', () => {
    expect(formatRelativeTime('2026-03-01T11:59:30Z', NOW)).toBe('30s ago');
  });

  it('formats sub-hour durations in minutes', () => {
    expect(formatRelativeTime('2026-03-01T11:45:00Z', NOW)).toBe('15m ago');
  });

  it('formats sub-day durations in hours', () => {
    expect(formatRelativeTime('2026-03-01T09:00:00Z', NOW)).toBe('3h ago');
  });

  it('formats multi-day durations in days', () => {
    expect(formatRelativeTime('2026-02-27T12:00:00Z', NOW)).toBe('2d ago');
  });

  it('follows the given locale rather than always formatting in English', () => {
    expect(formatRelativeTime('2026-03-01T11:45:00Z', NOW, 'ja')).toBe('15分前');
  });
});

describe('formatWeekday', () => {
  it('formats a plain date-only string as a short weekday name', () => {
    expect(formatWeekday('2026-03-08')).toBe('Sun');
  });

  it('follows the given locale', () => {
    expect(formatWeekday('2026-03-08', 'ja')).toBe('日');
  });

  it('treats the date as UTC so negative-offset zones do not shift it back a day', () => {
    // 2026-03-08 is a Sunday in UTC; a naive local-time parse in UTC-8 would read it as Saturday, so this only passes if UTC is honored.
    expect(formatWeekday('2026-03-08')).toBe(formatWeekday('2026-03-08T00:00:00Z'));
  });
});

describe('formatCountdown', () => {
  it('returns null when there is no target', () => {
    expect(formatCountdown(null, NOW)).toBeNull();
  });

  it('returns null once the target has passed', () => {
    expect(formatCountdown('2026-03-01T11:00:00Z', NOW)).toBeNull();
  });

  it('formats the remaining time as HH:MM:SS', () => {
    expect(formatCountdown('2026-03-01T13:02:03Z', NOW)).toBe('01:02:03');
  });
});

describe('nextOutstandingTarget', () => {
  it('returns null when every milestone is already achieved', () => {
    const target = nextOutstandingTarget({
      respondedAt: '2026-03-01T00:00:00Z',
      responseTargetAt: '2026-03-01T00:05:00Z',
      mitigatedAt: '2026-03-01T00:10:00Z',
      mitigationTargetAt: '2026-03-01T00:30:00Z',
      resolvedAt: '2026-03-01T01:00:00Z',
      resolutionTargetAt: '2026-03-01T04:00:00Z',
    });
    expect(target).toBeNull();
  });

  it('returns the response target when nothing has been achieved yet', () => {
    const target = nextOutstandingTarget({
      respondedAt: null,
      responseTargetAt: '2026-03-01T00:05:00Z',
      mitigatedAt: null,
      mitigationTargetAt: '2026-03-01T00:30:00Z',
      resolvedAt: null,
      resolutionTargetAt: '2026-03-01T04:00:00Z',
    });
    expect(target).toBe('2026-03-01T00:05:00Z');
  });

  it('skips achieved milestones and returns the next outstanding one', () => {
    const target = nextOutstandingTarget({
      respondedAt: '2026-03-01T00:04:00Z',
      responseTargetAt: '2026-03-01T00:05:00Z',
      mitigatedAt: null,
      mitigationTargetAt: '2026-03-01T00:30:00Z',
      resolvedAt: null,
      resolutionTargetAt: '2026-03-01T04:00:00Z',
    });
    expect(target).toBe('2026-03-01T00:30:00Z');
  });
});

describe('slaProgressPercent', () => {
  it('is 0% right at the start of the window', () => {
    expect(slaProgressPercent('2026-03-01T12:00:00Z', '2026-03-01T13:00:00Z', NOW)).toBe(0);
  });

  it('is 50% halfway through the window', () => {
    const now = new Date('2026-03-01T12:30:00Z');
    expect(slaProgressPercent('2026-03-01T12:00:00Z', '2026-03-01T13:00:00Z', now)).toBe(50);
  });

  it('clamps to 100% once the target has passed, rather than overflowing the bar', () => {
    const now = new Date('2026-03-01T14:00:00Z');
    expect(slaProgressPercent('2026-03-01T12:00:00Z', '2026-03-01T13:00:00Z', now)).toBe(100);
  });

  it('reads as fully elapsed when there is no start time to measure from', () => {
    expect(slaProgressPercent(null, '2026-03-01T13:00:00Z', NOW)).toBe(100);
  });

  it('reads as fully elapsed for a zero-length window instead of dividing by zero', () => {
    expect(slaProgressPercent('2026-03-01T13:00:00Z', '2026-03-01T13:00:00Z', NOW)).toBe(100);
  });
});
