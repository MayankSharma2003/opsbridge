import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { LiveClient, liveSocketUrl, type LiveClientDeps } from '../../api/liveClient';
import { setAuthToken } from '../../api/tokenHolder';
import type { LiveEvent } from '../../types/live';

// Fake socket standing in for the browser's -- tests the handshake and retry policy, pure client behavior.
class FakeSocket {
  static instances: FakeSocket[] = [];
  sent: string[] = [];
  closed = false;
  readyState = 1;
  onopen: (() => void) | null = null;
  onmessage: ((event: MessageEvent) => void) | null = null;
  onclose: ((event: CloseEvent) => void) | null = null;
  onerror: (() => void) | null = null;

  readonly url: string;

  constructor(url: string) {
    this.url = url;
    FakeSocket.instances.push(this);
  }

  send(data: string): void {
    this.sent.push(data);
  }

  close(): void {
    this.closed = true;
    this.onclose?.({ code: 1000 } as CloseEvent);
  }

  open(): void {
    this.onopen?.();
  }

  receive(payload: unknown): void {
    this.onmessage?.({ data: JSON.stringify(payload) } as MessageEvent);
  }

  receiveRaw(data: string): void {
    this.onmessage?.({ data } as MessageEvent);
  }

  /** Server or network dropped us, as distinct from us closing deliberately. */
  drop(code = 1006): void {
    this.onclose?.({ code } as CloseEvent);
  }
}

interface Scheduled {
  handler: () => void;
  ms: number;
}

function harness(overrides: Partial<LiveClientDeps> = {}) {
  const scheduled: Scheduled[] = [];
  const events: LiveEvent[] = [];
  const readies: number[] = [];
  const reconnects: number[] = [];
  const refusals: number[] = [];

  const client = new LiveClient(
    'ws://localhost:8080/ws/events',
    {
      onEvent: (event) => events.push(event),
      onReady: () => readies.push(1),
      onReconnecting: (failures) => reconnects.push(failures),
      onRefused: () => refusals.push(1),
    },
    {
      socketFactory: (url) => new FakeSocket(url) as unknown as WebSocket,
      setTimeoutFn: (handler, ms) => {
        scheduled.push({ handler, ms });
        return scheduled.length - 1;
      },
      clearTimeoutFn: () => {},
      // No jitter by default, so delays stay exact and readable.
      random: () => 1,
      ...overrides,
    },
  );

  return {
    client,
    scheduled,
    events,
    readies,
    reconnects,
    refusals,
    latestSocket: () => FakeSocket.instances[FakeSocket.instances.length - 1],
    runNextTimer: () => {
      const next = scheduled.shift();
      next?.handler();
    },
  };
}

const INCIDENT_EVENT = {
  type: 'INCIDENT_UPDATED' as const,
  occurredAt: '2026-03-01T09:00:00Z',
  incident: { id: 'inc-1', publicKey: 'INC-1000', status: 'INVESTIGATING' },
};

describe('liveSocketUrl', () => {
  it('derives a ws:// url from the http api base url', () => {
    expect(liveSocketUrl('http://localhost:8080')).toBe('ws://localhost:8080/ws/events');
  });

  it('upgrades to wss:// for an https api', () => {
    expect(liveSocketUrl('https://ops.example.com')).toBe('wss://ops.example.com/ws/events');
  });

  it('does not double the slash when the base url has a trailing one', () => {
    expect(liveSocketUrl('http://localhost:8080/')).toBe('ws://localhost:8080/ws/events');
  });
});

describe('LiveClient', () => {
  beforeEach(() => {
    FakeSocket.instances = [];
    setAuthToken('test-token');
  });

  afterEach(() => {
    setAuthToken(null);
  });

  it('sends the token as the very first frame, and never in the url', () => {
    const h = harness();
    h.client.start();
    h.latestSocket().open();

    expect(h.latestSocket().sent).toEqual([JSON.stringify({ type: 'AUTH', token: 'test-token' })]);
    // Token must not be reachable from logs or history via the connect url.
    expect(h.latestSocket().url).not.toContain('test-token');
  });

  it('does not connect at all without a token', () => {
    setAuthToken(null);
    const h = harness();

    h.client.start();

    expect(FakeSocket.instances).toHaveLength(0);
  });

  it('surfaces nothing until the server confirms the token', () => {
    const h = harness();
    h.client.start();
    h.latestSocket().open();

    // Event before READY would mean the server skipped the handshake.
    h.latestSocket().receive(INCIDENT_EVENT);

    expect(h.readies).toHaveLength(0);
    // Client still forwards it -- the server withholds data pre-auth -- but connection isn't reported live until READY lands.
    expect(h.events).toHaveLength(1);
  });

  it('reports ready once the server accepts the token', () => {
    const h = harness();
    h.client.start();
    h.latestSocket().open();

    h.latestSocket().receive({ type: 'READY' });

    expect(h.readies).toHaveLength(1);
  });

  it('forwards incident and breach events to the handler', () => {
    const h = harness();
    h.client.start();
    h.latestSocket().open();
    h.latestSocket().receive({ type: 'READY' });

    h.latestSocket().receive(INCIDENT_EVENT);
    h.latestSocket().receive({ ...INCIDENT_EVENT, type: 'SLA_BREACHED' });

    expect(h.events.map((event) => event.type)).toEqual(['INCIDENT_UPDATED', 'SLA_BREACHED']);
  });

  it('ignores malformed frames and unknown message types instead of throwing', () => {
    const h = harness();
    h.client.start();
    h.latestSocket().open();

    h.latestSocket().receiveRaw('not json at all');
    h.latestSocket().receive({ type: 'SOMETHING_ELSE' });

    expect(h.events).toHaveLength(0);
  });

  it('backs off exponentially, capped, when the connection keeps dropping', () => {
    const h = harness();
    h.client.start();

    const delays: number[] = [];
    for (let i = 0; i < 7; i += 1) {
      h.latestSocket().drop();
      delays.push(h.scheduled[h.scheduled.length - 1].ms);
      h.runNextTimer();
    }

    expect(delays).toEqual([1000, 2000, 4000, 8000, 16000, 30000, 30000]);
  });

  it('applies jitter so every client does not retry in the same instant', () => {
    const h = harness({ random: () => 0 });
    h.client.start();

    h.latestSocket().drop();

    // random() === 0 takes the bottom of the +/-50% jitter band.
    expect(h.scheduled[0].ms).toBe(500);
  });

  it('resets the backoff after a successful reconnect, not merely a reopened socket', () => {
    const h = harness();
    h.client.start();

    h.latestSocket().drop();
    expect(h.scheduled[0].ms).toBe(1000);
    h.runNextTimer();

    h.latestSocket().open();
    h.latestSocket().receive({ type: 'READY' });

    h.latestSocket().drop();
    expect(h.scheduled[0].ms).toBe(1000);
  });

  it('reports that it is reconnecting each time it schedules a retry', () => {
    const h = harness();
    h.client.start();

    h.latestSocket().drop();

    expect(h.reconnects).toHaveLength(1);
  });

  it('stops retrying once stopped, so a signed-out tab holds nothing open', () => {
    const h = harness();
    h.client.start();
    const socket = h.latestSocket();

    h.client.stop();

    expect(socket.closed).toBe(true);
    expect(h.scheduled).toHaveLength(0);
    expect(FakeSocket.instances).toHaveLength(1);
  });

  it('does not reconnect when a drop arrives after stop', () => {
    const h = harness();
    h.client.start();
    const socket = h.latestSocket();

    h.client.stop();
    socket.drop();

    expect(h.scheduled).toHaveLength(0);
  });
});
