import { describe, expect, it } from 'vitest';
import { ApiError, NetworkError } from '../../api/client';
import { mapApiErrorToKey } from '../../api/errorMessages';

// Every store funnels errors through here -- the one place deciding what the user sees; these tests cover mappings no store test hits.
describe('mapApiErrorToKey', () => {
  it('maps a stale-version conflict to its own key, not a generic failure', () => {
    expect(mapApiErrorToKey(new ApiError(409, 'STALE_VERSION', 'stale'))).toBe('errors.staleVersion');
  });

  it('maps an illegal transition to its own key', () => {
    expect(mapApiErrorToKey(new ApiError(422, 'ILLEGAL_STATUS_TRANSITION', 'nope'))).toBe('errors.illegalTransition');
  });

  it('maps a missing resource to its own key', () => {
    expect(mapApiErrorToKey(new ApiError(404, 'RESOURCE_NOT_FOUND', 'gone'))).toBe('errors.notFound');
  });

  it('maps a role rejection to its own key', () => {
    expect(mapApiErrorToKey(new ApiError(403, 'FORBIDDEN', 'denied'))).toBe('errors.forbidden');
  });

  it('maps a validation rejection to its own key', () => {
    expect(mapApiErrorToKey(new ApiError(400, 'VALIDATION_ERROR', 'bad'))).toBe('errors.validation');
  });

  it('distinguishes an unreachable server from a rejected request', () => {
    // "the server said no" and "there was no server" need different advice.
    expect(mapApiErrorToKey(new NetworkError())).toBe('errors.network');
  });

  it('falls back for an ApiError code it does not recognise', () => {
    expect(mapApiErrorToKey(new ApiError(500, 'INTERNAL_ERROR', 'boom'))).toBe('errors.unexpected');
  });

  it('falls back for something that is not an ApiError at all', () => {
    expect(mapApiErrorToKey(new TypeError('undefined is not a function'))).toBe('errors.unexpected');
    expect(mapApiErrorToKey('a bare string')).toBe('errors.unexpected');
    expect(mapApiErrorToKey(undefined)).toBe('errors.unexpected');
  });

  it('honours a caller-supplied fallback for unrecognised errors only', () => {
    expect(mapApiErrorToKey(new Error('boom'), 'errors.loadFailed')).toBe('errors.loadFailed');
    expect(mapApiErrorToKey(new ApiError(403, 'FORBIDDEN', 'x'), 'errors.loadFailed')).toBe('errors.forbidden');
  });

  it('never returns raw backend text, only i18n keys', () => {
    const key = mapApiErrorToKey(new ApiError(409, 'STALE_VERSION', 'Row was modified by admin@corp'));

    expect(key).not.toContain('admin@corp');
    expect(key.startsWith('errors.')).toBe(true);
  });
});
