import { makeAutoObservable, runInAction } from 'mobx';
import { getDashboardSummary } from '../api/dashboardApi';
import { listIncidents } from '../api/incidentApi';
import { mapApiErrorToKey } from '../api/errorMessages';
import type { DashboardSummary } from '../types/dashboard';
import type { IncidentSummary } from '../types/incident';
import type { FetchStatus } from './IncidentStore';

const RECENT_SIZE = 7;

/** KPI summary, trends, a recent-incidents feed, and refresh state (spec 7.2). */
export class DashboardStore {
  summary: DashboardSummary | null = null;
  status: FetchStatus = 'idle';
  error: string | null = null;
  lastRefreshedAt: Date | null = null;

  // Separate from IncidentStore's items on purpose -- reusing that store would clobber whatever
  // filter the user left set on the Incidents screen. Same API, separate state.
  recentIncidents: IncidentSummary[] = [];
  recentStatus: FetchStatus = 'idle';

  constructor() {
    makeAutoObservable(this);
  }

  // "Empty" = every real number is genuinely zero/null. Spec 4.2's data model can't tell an
  // incident-free day from an unpopulated database, so this is the best available signal.
  get isEmpty(): boolean {
    if (this.status !== 'loaded' || !this.summary) return false;
    const { kpis, priorityDistribution } = this.summary;
    return (
      kpis.openIncidents === 0 &&
      kpis.slaBreachesThisWeek === 0 &&
      kpis.mttrMinutes === null &&
      priorityDistribution.every((entry) => entry.count === 0)
    );
  }

  // Live event changed the aggregates, but they're server rollups (open counts, MTTR, etc.) that
  // can't be recomputed from one incident payload, so re-read them instead of guessing. Only
  // refetches if the dashboard is actually on screen; otherwise the next mount does it.
  markStale(): void {
    if (this.status === 'loaded') {
      void this.fetchSummary();
      void this.fetchRecentIncidents();
    }
  }

  // live channel back up; anything missed while down isn't replayed, so re-read
  refreshAfterReconnect(): void {
    if (this.status === 'loaded' || this.status === 'error') {
      void this.fetchSummary();
      void this.fetchRecentIncidents();
    }
  }

  // newest first -- the list endpoint already defaults to createdAt DESC
  async fetchRecentIncidents(): Promise<void> {
    this.recentStatus = 'loading';
    try {
      const result = await listIncidents({ size: RECENT_SIZE });
      runInAction(() => {
        this.recentIncidents = result.content;
        this.recentStatus = 'loaded';
      });
    } catch {
      runInAction(() => {
        // card shows its own error row -- KPIs above it stay usable
        this.recentStatus = 'error';
      });
    }
  }

  async fetchSummary(): Promise<void> {
    this.status = 'loading';
    this.error = null;
    try {
      const summary = await getDashboardSummary();
      runInAction(() => {
        this.summary = summary;
        this.status = 'loaded';
        this.lastRefreshedAt = new Date();
      });
    } catch (error) {
      runInAction(() => {
        this.status = 'error';
        this.error = mapApiErrorToKey(error, 'errors.loadFailed');
      });
    }
  }
}
