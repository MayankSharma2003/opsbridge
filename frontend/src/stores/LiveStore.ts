import { makeAutoObservable, runInAction } from 'mobx';
import { LiveClient, liveSocketUrl, type LiveClientDeps } from '../api/liveClient';
import type { LiveEvent, LiveStatus } from '../types/live';
import type { IncidentStore } from './IncidentStore';
import type { DashboardStore } from './DashboardStore';

// Owns the live channel's connection state only (spec 7.2) -- routes events to the stores that
// hold data, through their public methods, rather than holding any itself. Reconnect triggers a
// refetch because there's no server-side replay: the socket is just an invalidation hint, and the
// REST API is the source of truth, so a dropped event only makes things stale until the next sync.
// how many failed attempts before the indicator stops saying "connecting" and admits it's down
const FAILURES_BEFORE_REPORTING_OFFLINE = 3;

export class LiveStore {
  status: LiveStatus = 'idle';
  lastEventAt: Date | null = null;

  private client: LiveClient | null = null;
  private hasConnectedBefore = false;
  private readonly incidentStore: IncidentStore;
  private readonly dashboardStore: DashboardStore;
  private readonly deps: Partial<LiveClientDeps>;

  constructor(
    incidentStore: IncidentStore,
    dashboardStore: DashboardStore,
    deps: Partial<LiveClientDeps> = {},
  ) {
    this.incidentStore = incidentStore;
    this.dashboardStore = dashboardStore;
    this.deps = deps;
    makeAutoObservable(this, {}, { autoBind: true });
  }

  get isLive(): boolean {
    return this.status === 'live';
  }

  connect(baseUrl: string = import.meta.env.VITE_API_BASE_URL): void {
    if (this.client) return;
    this.status = 'connecting';
    this.client = new LiveClient(
      liveSocketUrl(baseUrl),
      {
        onEvent: this.handleEvent,
        onReady: this.handleReady,
        onReconnecting: this.handleReconnecting,
        onRefused: this.handleRefused,
      },
      this.deps,
    );
    this.client.start();
  }

  // called on logout -- a signed-out tab must not keep an authenticated socket open
  disconnect(): void {
    this.client?.stop();
    this.client = null;
    this.hasConnectedBefore = false;
    this.status = 'idle';
  }

  handleEvent(event: LiveEvent): void {
    runInAction(() => {
      this.lastEventAt = new Date();
    });
    // both event types affect list + KPIs: status changes move open/resolved, breaches move the count
    this.incidentStore.applyLiveIncidentUpdate(event.incident);
    this.dashboardStore.markStale();
  }

  handleReady(): void {
    const isReconnect = this.hasConnectedBefore;
    runInAction(() => {
      this.status = 'live';
      this.hasConnectedBefore = true;
    });
    // only on a *re*connect -- the first connection happens alongside pages' own initial loads
    if (isReconnect) {
      this.incidentStore.refreshAfterReconnect();
      this.dashboardStore.refreshAfterReconnect();
    }
  }

  // Quiet retry is fine for a blip, but not forever -- if the server keeps refusing the endpoint
  // (old build, proxy in the way), tell the user live updates are down instead of spinning forever.
  handleReconnecting(consecutiveFailures: number): void {
    runInAction(() => {
      if (consecutiveFailures >= FAILURES_BEFORE_REPORTING_OFFLINE) {
        this.status = 'offline';
      } else {
        this.status = this.hasConnectedBefore ? 'reconnecting' : 'connecting';
      }
    });
  }

  // server rejected the token outright; client has stopped retrying
  handleRefused(): void {
    runInAction(() => {
      this.status = 'offline';
    });
  }
}
