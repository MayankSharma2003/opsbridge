import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../api/incidentApi', () => ({
  listIncidents: vi.fn(),
  getIncident: vi.fn(),
  getIncidentActivity: vi.fn(),
  transitionIncident: vi.fn(),
  assignIncident: vi.fn(),
}));
vi.mock('../../api/dashboardApi', () => ({
  getDashboardSummary: vi.fn(),
}));

import { listIncidents } from '../../api/incidentApi';
import { getDashboardSummary } from '../../api/dashboardApi';
import { LiveStore } from '../../stores/LiveStore';
import { IncidentStore } from '../../stores/IncidentStore';
import { DashboardStore } from '../../stores/DashboardStore';
import { setAuthToken } from '../../api/tokenHolder';
import type { LiveClientDeps } from '../../api/liveClient';
import type { IncidentSummary } from '../../types/incident';
import type { LiveEvent } from '../../types/live';

const listIncidentsMock = vi.mocked(listIncidents);
const getDashboardSummaryMock = vi.mocked(getDashboardSummary);

class FakeSocket {
  static instances: FakeSocket[] = [];
  closed = false;
  onopen: (() => void) | null = null;
  onmessage: ((event: MessageEvent) => void) | null = null;
  onclose: ((event: CloseEvent) => void) | null = null;
  onerror: (() => void) | null = null;

  url: string;

  constructor(url: string) {
    this.url = url;
    FakeSocket.instances.push(this);
  }

  send(): void {}

  readyState = 1;

  close(): void {
    this.closed = true;
    this.onclose?.({ code: 1000 } as CloseEvent);
  }

  handshake(): void {
    this.onopen?.();
    this.onmessage?.({ data: JSON.stringify({ type: 'READY' }) } as MessageEvent);
  }

  drop(code = 1006): void {
    this.onclose?.({ code } as CloseEvent);
  }
}

function incident(overrides: Partial<IncidentSummary> = {}): IncidentSummary {
  return {
    id: 'inc-1',
    publicKey: 'INC-1000',
    title: 'Checkout latency',
    service: { id: 'svc-1', name: 'Checkout API' },
    assignee: null,
    priority: 'P1',
    severity: 'SEV1',
    status: 'OPEN',
    slaState: 'ON_TRACK',
    slaResolutionTargetAt: null,
    version: 1,
    createdAt: '2026-03-01T08:00:00Z',
    updatedAt: '2026-03-01T08:00:00Z',
    ...overrides,
  };
}

function event(incidentPayload: IncidentSummary, type: LiveEvent['type'] = 'INCIDENT_UPDATED'): LiveEvent {
  return { type, occurredAt: '2026-03-01T09:00:00Z', incident: incidentPayload };
}

function page(content: IncidentSummary[]) {
  return {
    content,
    page: 0,
    size: 25,
    totalElements: content.length,
    totalPages: 1,
    first: true,
    last: true,
  };
}

describe('LiveStore', () => {
  let incidentStore: IncidentStore;
  let dashboardStore: DashboardStore;
  let liveStore: LiveStore;
  let scheduled: (() => void)[];

  const deps = (): Partial<LiveClientDeps> => ({
    socketFactory: (url) => new FakeSocket(url) as unknown as WebSocket,
    setTimeoutFn: (handler) => {
      scheduled.push(handler);
      return scheduled.length - 1;
    },
    clearTimeoutFn: () => {},
    random: () => 1,
  });

  beforeEach(() => {
    FakeSocket.instances = [];
    scheduled = [];
    listIncidentsMock.mockReset();
    getDashboardSummaryMock.mockReset();
    listIncidentsMock.mockResolvedValue(page([]));
    getDashboardSummaryMock.mockResolvedValue({
      kpis: { openIncidents: 1, unassignedIncidents: 0, slaBreachesThisWeek: 0, mttrMinutes: null },
      priorityDistribution: [],
      mttrTrend: [],
    });
    setAuthToken('test-token');
    incidentStore = new IncidentStore();
    dashboardStore = new DashboardStore();
    liveStore = new LiveStore(incidentStore, dashboardStore, deps());
  });

  afterEach(() => {
    setAuthToken(null);
  });

  const latestSocket = () => FakeSocket.instances[FakeSocket.instances.length - 1];

  it('reports connecting before the server has accepted the token', () => {
    liveStore.connect('http://localhost:8080');

    expect(liveStore.status).toBe('connecting');
    expect(liveStore.isLive).toBe(false);
  });

  it('goes live once the handshake completes', () => {
    liveStore.connect('http://localhost:8080');
    latestSocket().handshake();

    expect(liveStore.status).toBe('live');
    expect(liveStore.isLive).toBe(true);
  });

  it('connects to the websocket url derived from the api base url', () => {
    liveStore.connect('http://localhost:8080');

    expect(latestSocket().url).toBe('ws://localhost:8080/ws/events');
  });

  it('does not open a second connection if asked to connect twice', () => {
    liveStore.connect('http://localhost:8080');
    liveStore.connect('http://localhost:8080');

    expect(FakeSocket.instances).toHaveLength(1);
  });

  it('updates a matching row in the incident list when an event arrives', async () => {
    listIncidentsMock.mockResolvedValue(page([incident()]));
    await incidentStore.fetchList();
    liveStore.connect('http://localhost:8080');
    latestSocket().handshake();

    liveStore.handleEvent(event(incident({ status: 'RESOLVED', version: 4 })));

    expect(incidentStore.items[0].status).toBe('RESOLVED');
    expect(incidentStore.items[0].version).toBe(4);
    expect(liveStore.lastEventAt).toBeInstanceOf(Date);
  });

  it('does not insert an unknown incident into a filtered list', async () => {
    listIncidentsMock.mockResolvedValue(page([incident({ id: 'inc-1' })]));
    await incidentStore.fetchList();

    liveStore.handleEvent(event(incident({ id: 'inc-999' })));

    // Inserting would ignore the active filters, sort and page boundary.
    expect(incidentStore.items).toHaveLength(1);
    expect(incidentStore.items[0].id).toBe('inc-1');
  });

  it('refetches the dashboard when an event lands while it is on screen', async () => {
    await dashboardStore.fetchSummary();
    expect(getDashboardSummaryMock).toHaveBeenCalledTimes(1);

    liveStore.handleEvent(event(incident(), 'SLA_BREACHED'));

    expect(getDashboardSummaryMock).toHaveBeenCalledTimes(2);
  });

  it('leaves the dashboard alone when it has never been loaded', () => {
    liveStore.handleEvent(event(incident(), 'SLA_BREACHED'));

    expect(getDashboardSummaryMock).not.toHaveBeenCalled();
  });

  it('does not refetch on the first connection, because the pages are already loading', async () => {
    listIncidentsMock.mockResolvedValue(page([incident()]));
    await incidentStore.fetchList();
    listIncidentsMock.mockClear();

    liveStore.connect('http://localhost:8080');
    latestSocket().handshake();

    expect(listIncidentsMock).not.toHaveBeenCalled();
  });

  it('refetches after a reconnect, since events missed while down are never replayed', async () => {
    listIncidentsMock.mockResolvedValue(page([incident()]));
    await incidentStore.fetchList();
    await dashboardStore.fetchSummary();

    liveStore.connect('http://localhost:8080');
    latestSocket().handshake();
    listIncidentsMock.mockClear();
    getDashboardSummaryMock.mockClear();

    latestSocket().drop();
    expect(liveStore.status).toBe('reconnecting');
    scheduled.shift()?.();
    latestSocket().handshake();

    expect(liveStore.status).toBe('live');
    expect(getDashboardSummaryMock).toHaveBeenCalledTimes(1);
    // Two consumers re-read on reconnect: the incident list, and the dashboard's recent-incidents feed with its own page size.
    expect(listIncidentsMock).toHaveBeenCalledTimes(2);
    expect(listIncidentsMock).toHaveBeenCalledWith(expect.objectContaining({ size: 7 }));
    expect(listIncidentsMock).toHaveBeenCalledWith(expect.objectContaining({ page: 0, size: 25 }));
  });

  it('still reads as connecting, not reconnecting, if it never got live in the first place', () => {
    liveStore.connect('http://localhost:8080');

    latestSocket().drop();

    expect(liveStore.status).toBe('connecting');
  });

  it('closes the socket and goes idle on disconnect', () => {
    liveStore.connect('http://localhost:8080');
    latestSocket().handshake();
    const socket = latestSocket();

    liveStore.disconnect();

    expect(socket.closed).toBe(true);
    expect(liveStore.status).toBe('idle');
  });

  it('can be reconnected after a disconnect, and treats it as a fresh first connection', () => {
    liveStore.connect('http://localhost:8080');
    latestSocket().handshake();
    liveStore.disconnect();
    listIncidentsMock.mockClear();

    liveStore.connect('http://localhost:8080');
    latestSocket().handshake();

    expect(liveStore.status).toBe('live');
    expect(listIncidentsMock).not.toHaveBeenCalled();
  });

  it('gives up and reports offline when the server refuses the token', () => {
    liveStore.connect('http://localhost:8080');

    // 4401 means the server never accepted this token -- retrying would just burn the connection budget behind a spinner that never resolves.
    latestSocket().drop(4401);

    expect(liveStore.status).toBe('offline');
    expect(scheduled).toHaveLength(0);
    expect(FakeSocket.instances).toHaveLength(1);
  });

  it('admits it is offline after three failed attempts, but keeps retrying underneath', () => {
    liveStore.connect('http://localhost:8080');

    latestSocket().drop();
    expect(liveStore.status).toBe('connecting');
    scheduled.shift()?.();
    latestSocket().drop();
    expect(liveStore.status).toBe('connecting');
    scheduled.shift()?.();
    latestSocket().drop();

    expect(liveStore.status).toBe('offline');
    // Offline is just what the user is told -- a retry is still pending, so recovery stays automatic.
    expect(scheduled).toHaveLength(1);

    scheduled.shift()?.();
    latestSocket().handshake();
    expect(liveStore.status).toBe('live');
  });
});
