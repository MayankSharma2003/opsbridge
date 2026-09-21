import { describe, expect, it } from 'vitest';
import { healthFor, healthLabelKey } from '../../utils/serviceHealth';

describe('healthFor', () => {
  it('is nominal when nothing is open', () => {
    expect(healthFor(0, 0)).toBe('ok');
  });

  it('is critical when any open incident is P1/P2', () => {
    expect(healthFor(1, 1)).toBe('crit');
    expect(healthFor(9, 1)).toBe('crit');
  });

  it('is watching when work is open but none of it is urgent', () => {
    expect(healthFor(3, 0)).toBe('risk');
  });

  it('treats urgency as the deciding signal, not volume', () => {
    // One P1 outranks a pile of routine work.
    expect(healthFor(20, 0)).toBe('risk');
    expect(healthFor(1, 1)).toBe('crit');
  });
});

describe('healthLabelKey', () => {
  it('maps each health to its own i18n key', () => {
    expect(healthLabelKey('crit')).toBe('pages.services.healthCritical');
    expect(healthLabelKey('risk')).toBe('pages.services.healthWatching');
    expect(healthLabelKey('ok')).toBe('pages.services.healthNominal');
  });
});
