// In-memory copy of the bearer token; AuthStore owns when it's set and where it's persisted.
// Exists so the API client can read it without importing the store, which would cycle api/ <-> stores/.
let currentToken: string | null = null;

export function setAuthToken(token: string | null): void {
  currentToken = token;
}

export function getAuthToken(): string | null {
  return currentToken;
}
