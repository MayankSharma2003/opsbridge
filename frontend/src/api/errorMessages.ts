import { ApiError, NetworkError } from './client';

// maps to an i18n key, never raw backend text -- keeps stores translation-agnostic, like AuthStore
export function mapApiErrorToKey(error: unknown, fallback = 'errors.unexpected'): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'STALE_VERSION':
        return 'errors.staleVersion';
      case 'ILLEGAL_STATUS_TRANSITION':
        return 'errors.illegalTransition';
      case 'RESOURCE_NOT_FOUND':
        return 'errors.notFound';
      case 'FORBIDDEN':
        return 'errors.forbidden';
      case 'VALIDATION_ERROR':
        return 'errors.validation';
      default:
        return fallback;
    }
  }
  if (error instanceof NetworkError) {
    return 'errors.network';
  }
  return fallback;
}
