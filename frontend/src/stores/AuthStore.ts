import { makeAutoObservable, runInAction } from 'mobx';
import { ApiError, NetworkError } from '../api/client';
import { login as loginRequest, me as meRequest } from '../api/authApi';
import { setAuthToken } from '../api/tokenHolder';
import type { AuthenticatedUser, Role } from '../types/auth';

const STORAGE_KEY = 'opsbridge.auth';

interface PersistedSession {
  token: string;
  expiresAt: string;
  user: AuthenticatedUser;
}

type SessionStatus = 'restoring' | 'ready';

// Owns the current user, bearer token, and where it lives (spec 7.2). Never makes an authorization
// decision itself -- the backend re-checks every mutation regardless of what this store shows.
// No refresh-token/httpOnly-cookie flow exists, so client storage is the only option, and
// localStorage vs sessionStorage doesn't change the XSS threat model (both equally readable) --
// it only changes lifetime, which is what "remember me" actually controls.
export class AuthStore {
  user: AuthenticatedUser | null = null;
  token: string | null = null;
  sessionStatus: SessionStatus = 'restoring';
  loginPending = false;
  // i18n key, not display text -- keeps the store translation-agnostic
  loginError: string | null = null;

  constructor() {
    makeAutoObservable(this);
  }

  get isAuthenticated(): boolean {
    return this.user !== null;
  }

  get role(): Role | null {
    return this.user?.role ?? null;
  }

  // runs once at boot; trusts a stored token only after GET /me confirms it's still valid
  async restoreSession(): Promise<void> {
    const persisted = readPersisted();
    if (!persisted || new Date(persisted.expiresAt).getTime() <= Date.now()) {
      clearPersisted();
      runInAction(() => {
        this.sessionStatus = 'ready';
      });
      return;
    }

    setAuthToken(persisted.token);
    try {
      const confirmed = await meRequest();
      runInAction(() => {
        this.token = persisted.token;
        this.user = confirmed;
        this.sessionStatus = 'ready';
      });
    } catch {
      setAuthToken(null);
      clearPersisted();
      runInAction(() => {
        this.sessionStatus = 'ready';
      });
    }
  }

  async login(email: string, password: string, remember: boolean): Promise<void> {
    this.loginPending = true;
    this.loginError = null;
    try {
      const response = await loginRequest(email, password);
      const user: AuthenticatedUser = {
        id: response.userId,
        name: response.name,
        email: response.email,
        role: response.role,
      };
      setAuthToken(response.token);
      persist({ token: response.token, expiresAt: response.expiresAt, user }, remember);
      runInAction(() => {
        this.token = response.token;
        this.user = user;
        this.loginPending = false;
      });
    } catch (error) {
      runInAction(() => {
        this.loginPending = false;
        this.loginError = mapLoginError(error);
      });
      throw error;
    }
  }

  logout(): void {
    setAuthToken(null);
    clearPersisted();
    this.token = null;
    this.user = null;
  }
}

function mapLoginError(error: unknown): string {
  if (error instanceof ApiError && error.status === 401) {
    return 'auth.login.invalidCredentials';
  }
  if (error instanceof NetworkError) {
    return 'auth.login.networkError';
  }
  return 'auth.login.unexpectedError';
}

function persist(session: PersistedSession, remember: boolean): void {
  clearPersisted();
  const storage = remember ? localStorage : sessionStorage;
  storage.setItem(STORAGE_KEY, JSON.stringify(session));
}

function readPersisted(): PersistedSession | null {
  for (const storage of [localStorage, sessionStorage]) {
    const raw = storage.getItem(STORAGE_KEY);
    if (!raw) continue;
    try {
      return JSON.parse(raw) as PersistedSession;
    } catch {
      storage.removeItem(STORAGE_KEY);
    }
  }
  return null;
}

function clearPersisted(): void {
  localStorage.removeItem(STORAGE_KEY);
  sessionStorage.removeItem(STORAGE_KEY);
}
