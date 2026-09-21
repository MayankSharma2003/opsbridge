import { getAuthToken } from './tokenHolder';
import type { LiveEvent } from '../types/live';

// Framework-free websocket client -- one push-only channel, so a library would be overkill.
// Token goes in the first frame, not the URL: WebSocket can't set an Authorization header, and
// `?access_token=...` would leak the token into server logs and browser history.

const INITIAL_RETRY_MS = 1_000;
const MAX_RETRY_MS = 30_000;

/** The server's "you never authenticated" close code (LiveEventBroadcaster.UNAUTHENTICATED). */
const UNAUTHENTICATED_CLOSE = 4401;

// avoids depending on the WebSocket global at import time
const WEBSOCKET_CONNECTING = 0;

export interface LiveClientHandlers {
  onEvent: (event: LiveEvent) => void;
  /** Fired once per successful (re)connection, after the server accepts the token. */
  onReady: () => void;
  // consecutiveFailures counts attempts that never reached a handshake, so callers can tell a blip
  // from something durably wrong.
  onReconnecting: (consecutiveFailures: number) => void;
  // server explicitly refused the token -- retrying the same one is pointless, so we stop
  onRefused: () => void;
}

// injectable so tests can drive timing/sockets without a real server or real waiting
export interface LiveClientDeps {
  socketFactory: (url: string) => WebSocket;
  setTimeoutFn: (handler: () => void, ms: number) => number;
  clearTimeoutFn: (handle: number) => void;
  random: () => number;
}

const defaultDeps: LiveClientDeps = {
  socketFactory: (url) => new WebSocket(url),
  setTimeoutFn: (handler, ms) => globalThis.setTimeout(handler, ms) as unknown as number,
  clearTimeoutFn: (handle) => globalThis.clearTimeout(handle),
  random: Math.random,
};

// derived from the API base URL so there's no second env var to keep in sync
export function liveSocketUrl(apiBaseUrl: string): string {
  return `${apiBaseUrl.replace(/^http/, 'ws').replace(/\/$/, '')}/ws/events`;
}

export class LiveClient {
  private socket: WebSocket | null = null;
  private retryHandle: number | null = null;
  private attempt = 0;
  private stopped = true;
  private readonly deps: LiveClientDeps;
  private readonly url: string;
  private readonly handlers: LiveClientHandlers;

  constructor(url: string, handlers: LiveClientHandlers, deps: Partial<LiveClientDeps> = {}) {
    this.url = url;
    this.handlers = handlers;
    this.deps = { ...defaultDeps, ...deps };
  }

  start(): void {
    this.stopped = false;
    this.open();
  }

  // closes for good, no retries -- used on logout so a signed-out tab holds nothing open
  stop(): void {
    this.stopped = true;
    this.cancelRetry();
    const socket = this.socket;
    this.socket = null;
    if (socket) this.closeSocket(socket);
  }

  // Closing mid-handshake logs a scary "closed before connection established" warning, which
  // StrictMode triggers on every dev mount. Let the handshake finish, then close normally.
  private closeSocket(socket: WebSocket): void {
    if (socket.readyState === WEBSOCKET_CONNECTING) {
      socket.onmessage = null;
      socket.onclose = null;
      socket.onopen = () => socket.close();
      return;
    }
    socket.close();
  }

  /** Visible for tests -- the backoff schedule is behaviour worth pinning down. */
  retryDelayMs(attempt: number): number {
    const exponential = Math.min(INITIAL_RETRY_MS * 2 ** attempt, MAX_RETRY_MS);
    // jitter so a backend restart doesn't bring every browser back at once
    return Math.round(exponential * (0.5 + this.deps.random() * 0.5));
  }

  private open(): void {
    const token = getAuthToken();
    // No token means no point connecting; the server would just close us.
    if (this.stopped || !token) return;

    const socket = this.deps.socketFactory(this.url);
    this.socket = socket;

    socket.onopen = () => {
      // first frame, always -- socket is inert until the server accepts this
      socket.send(JSON.stringify({ type: 'AUTH', token }));
    };

    socket.onmessage = (event: MessageEvent) => {
      this.handleMessage(String(event.data));
    };

    socket.onclose = (event: CloseEvent) => {
      if (this.socket !== socket) return;
      this.socket = null;

      // a refused token is a decision, not an outage -- retrying would just hide it behind a spinner
      if (event?.code === UNAUTHENTICATED_CLOSE) {
        this.stopped = true;
        this.handlers.onRefused();
        return;
      }
      this.scheduleRetry();
    };

    socket.onerror = () => {
      // onclose always follows, so retry scheduling lives there and can't double-fire.
    };
  }

  private handleMessage(raw: string): void {
    let parsed: { type?: string };
    try {
      parsed = JSON.parse(raw) as { type?: string };
    } catch {
      return;
    }

    if (parsed.type === 'READY') {
      // only a server-accepted token clears the backoff, not just reaching the socket
      this.attempt = 0;
      this.handlers.onReady();
      return;
    }
    if (parsed.type === 'INCIDENT_UPDATED' || parsed.type === 'SLA_BREACHED') {
      this.handlers.onEvent(parsed as unknown as LiveEvent);
    }
  }

  private scheduleRetry(): void {
    if (this.stopped || this.retryHandle !== null) return;
    const delay = this.retryDelayMs(this.attempt);
    this.attempt += 1;
    this.handlers.onReconnecting(this.attempt);
    this.retryHandle = this.deps.setTimeoutFn(() => {
      this.retryHandle = null;
      this.open();
    }, delay);
  }

  private cancelRetry(): void {
    if (this.retryHandle !== null) {
      this.deps.clearTimeoutFn(this.retryHandle);
      this.retryHandle = null;
    }
    this.attempt = 0;
  }
}
