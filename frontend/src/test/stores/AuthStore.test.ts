import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, NetworkError } from '../../api/client';
import { getAuthToken, setAuthToken } from '../../api/tokenHolder';

vi.mock('../../api/authApi', () => ({
  login: vi.fn(),
  me: vi.fn(),
}));

import { login, me } from '../../api/authApi';
import { AuthStore } from '../../stores/AuthStore';

const loginMock = vi.mocked(login);
const meMock = vi.mocked(me);

const LOGIN_RESPONSE = {
  token: 'jwt-token',
  tokenType: 'Bearer',
  expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
  userId: 'user-1',
  name: 'Dev Operator',
  email: 'operator@opsbridge.local',
  role: 'OPERATOR' as const,
};

describe('AuthStore', () => {
  let authStore: AuthStore;

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    setAuthToken(null);
    loginMock.mockReset();
    meMock.mockReset();
    authStore = new AuthStore();
  });

  describe('login', () => {
    it('sets the user, token, and clears pending/error state on success', async () => {
      loginMock.mockResolvedValue(LOGIN_RESPONSE);

      await authStore.login('operator@opsbridge.local', 'Operator#Dev2026', true);

      expect(authStore.isAuthenticated).toBe(true);
      expect(authStore.role).toBe('OPERATOR');
      expect(authStore.user?.name).toBe('Dev Operator');
      expect(authStore.loginPending).toBe(false);
      expect(authStore.loginError).toBeNull();
      expect(getAuthToken()).toBe('jwt-token');
    });

    it('persists to localStorage when remember is true', async () => {
      loginMock.mockResolvedValue(LOGIN_RESPONSE);

      await authStore.login('operator@opsbridge.local', 'Operator#Dev2026', true);

      expect(localStorage.getItem('opsbridge.auth')).toContain('jwt-token');
      expect(sessionStorage.getItem('opsbridge.auth')).toBeNull();
    });

    it('persists to sessionStorage, not localStorage, when remember is false', async () => {
      loginMock.mockResolvedValue(LOGIN_RESPONSE);

      await authStore.login('operator@opsbridge.local', 'Operator#Dev2026', false);

      expect(sessionStorage.getItem('opsbridge.auth')).toContain('jwt-token');
      expect(localStorage.getItem('opsbridge.auth')).toBeNull();
    });

    it('maps a 401 to the invalid-credentials i18n key without setting a user', async () => {
      loginMock.mockRejectedValue(new ApiError(401, 'INVALID_CREDENTIALS', 'Email or password is incorrect.'));

      await expect(authStore.login('operator@opsbridge.local', 'wrong', false)).rejects.toThrow();

      expect(authStore.isAuthenticated).toBe(false);
      expect(authStore.loginPending).toBe(false);
      expect(authStore.loginError).toBe('auth.login.invalidCredentials');
    });

    it('maps a network failure to the network-error i18n key', async () => {
      loginMock.mockRejectedValue(new NetworkError());

      await expect(authStore.login('operator@opsbridge.local', 'x', false)).rejects.toThrow();

      expect(authStore.loginError).toBe('auth.login.networkError');
    });

    it('sets loginPending while the request is in flight', () => {
      let resolveLogin: (value: typeof LOGIN_RESPONSE) => void = () => {};
      loginMock.mockReturnValue(new Promise((resolve) => (resolveLogin = resolve)));

      const pending = authStore.login('operator@opsbridge.local', 'x', false);
      expect(authStore.loginPending).toBe(true);

      resolveLogin(LOGIN_RESPONSE);
      return pending;
    });
  });

  describe('logout', () => {
    it('clears user, token, and both storages', async () => {
      loginMock.mockResolvedValue(LOGIN_RESPONSE);
      await authStore.login('operator@opsbridge.local', 'Operator#Dev2026', true);

      authStore.logout();

      expect(authStore.isAuthenticated).toBe(false);
      expect(authStore.token).toBeNull();
      expect(getAuthToken()).toBeNull();
      expect(localStorage.getItem('opsbridge.auth')).toBeNull();
    });
  });

  describe('restoreSession', () => {
    it('goes straight to ready, unauthenticated, without calling /me when nothing is stored', async () => {
      await authStore.restoreSession();

      expect(authStore.sessionStatus).toBe('ready');
      expect(authStore.isAuthenticated).toBe(false);
      expect(meMock).not.toHaveBeenCalled();
    });

    it('confirms a valid stored token via /me and adopts its response as the user', async () => {
      localStorage.setItem(
        'opsbridge.auth',
        JSON.stringify({
          token: 'stored-token',
          expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
          user: { id: 'stale-id', name: 'Stale Name', email: 'x@opsbridge.local', role: 'VIEWER' },
        }),
      );
      meMock.mockResolvedValue({ id: 'user-1', name: 'Fresh Name', email: 'operator@opsbridge.local', role: 'OPERATOR' });

      await authStore.restoreSession();

      expect(authStore.sessionStatus).toBe('ready');
      expect(authStore.isAuthenticated).toBe(true);
      // Identity comes from the live /me call, not the possibly-stale persisted copy.
      expect(authStore.user?.name).toBe('Fresh Name');
      expect(getAuthToken()).toBe('stored-token');
    });

    it('discards an expired stored token without calling /me', async () => {
      localStorage.setItem(
        'opsbridge.auth',
        JSON.stringify({
          token: 'stored-token',
          expiresAt: new Date(Date.now() - 1_000).toISOString(),
          user: { id: 'user-1', name: 'X', email: 'x@opsbridge.local', role: 'VIEWER' },
        }),
      );

      await authStore.restoreSession();

      expect(authStore.isAuthenticated).toBe(false);
      expect(meMock).not.toHaveBeenCalled();
      expect(localStorage.getItem('opsbridge.auth')).toBeNull();
    });

    it('clears the stored session when /me rejects it (e.g. a deactivated account)', async () => {
      localStorage.setItem(
        'opsbridge.auth',
        JSON.stringify({
          token: 'stored-token',
          expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
          user: { id: 'user-1', name: 'X', email: 'x@opsbridge.local', role: 'VIEWER' },
        }),
      );
      meMock.mockRejectedValue(new ApiError(401, 'UNAUTHENTICATED', 'Authentication is required.'));

      await authStore.restoreSession();

      expect(authStore.isAuthenticated).toBe(false);
      expect(authStore.sessionStatus).toBe('ready');
      expect(getAuthToken()).toBeNull();
      expect(localStorage.getItem('opsbridge.auth')).toBeNull();
    });
  });
});
