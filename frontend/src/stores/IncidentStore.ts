import { makeAutoObservable, runInAction } from 'mobx';
import {
  assignIncident,
  createIncident,
  getIncident,
  getIncidentActivity,
  listIncidents,
  transitionIncident,
} from '../api/incidentApi';
import { mapApiErrorToKey } from '../api/errorMessages';
import type {
  AssignIncidentRequest,
  CreateIncidentRequest,
  IncidentActivityEntry,
  IncidentDetail,
  IncidentSearchParams,
  IncidentSummary,
  TransitionIncidentRequest,
} from '../types/incident';

export type FetchStatus = 'idle' | 'loading' | 'loaded' | 'error';

const DEFAULT_SIZE = 25;
const TERMINAL_STATUSES = new Set(['RESOLVED', 'CANCELLED']);

// Incident list, filters, pagination, detail, and workflow mutations (spec 7.2). Never decides
// authorization -- every mutation still goes to the real backend, which is free to refuse it.
export class IncidentStore {
  items: IncidentSummary[] = [];
  totalElements = 0;
  totalPages = 0;
  page = 0;
  size = DEFAULT_SIZE;
  filters: IncidentSearchParams = {};
  listStatus: FetchStatus = 'idle';
  listError: string | null = null;

  detail: IncidentDetail | null = null;
  detailStatus: FetchStatus = 'idle';
  detailError: string | null = null;

  activity: IncidentActivityEntry[] = [];
  activityStatus: FetchStatus = 'idle';

  mutationPending = false;
  mutationError: string | null = null;

  // orders concurrent background re-reads so a slow one can't overwrite a newer one
  private detailRefreshSeq = 0;

  constructor() {
    makeAutoObservable(this);
  }

  get isListEmpty(): boolean {
    return this.listStatus === 'loaded' && this.items.length === 0;
  }

  // Page-scoped counts for the list subtitle -- isSinglePage tells the page whether quoting these
  // next to the whole-result-set total is comparing apples to apples.
  get openCount(): number {
    return this.items.filter((incident) => !TERMINAL_STATUSES.has(incident.status)).length;
  }

  get breachedCount(): number {
    return this.items.filter((incident) => incident.slaState === 'BREACHED').length;
  }

  // true when the loaded page holds every matching incident, so page-scoped counts are the totals
  get isSinglePage(): boolean {
    return this.items.length === this.totalElements;
  }

  async fetchList(): Promise<void> {
    this.listStatus = 'loading';
    this.listError = null;
    try {
      const result = await listIncidents({ ...this.filters, page: this.page, size: this.size });
      runInAction(() => {
        this.items = result.content;
        this.totalElements = result.totalElements;
        this.totalPages = result.totalPages;
        this.listStatus = 'loaded';
      });
    } catch (error) {
      runInAction(() => {
        this.listStatus = 'error';
        this.listError = mapApiErrorToKey(error, 'errors.loadFailed');
      });
    }
  }

  setFilters(patch: Partial<IncidentSearchParams>): void {
    this.filters = { ...this.filters, ...patch };
    this.page = 0;
    void this.fetchList();
  }

  clearFilters(): void {
    this.filters = {};
    this.page = 0;
    void this.fetchList();
  }

  goToPage(page: number): void {
    this.page = page;
    void this.fetchList();
  }

  async fetchDetail(id: string): Promise<void> {
    this.detailStatus = 'loading';
    this.detailError = null;
    this.detail = null;
    this.activity = [];
    try {
      const detail = await getIncident(id);
      runInAction(() => {
        this.detail = detail;
        this.detailStatus = 'loaded';
      });
    } catch (error) {
      runInAction(() => {
        this.detailStatus = 'error';
        this.detailError = mapApiErrorToKey(error, 'errors.loadFailed');
      });
    }
    void this.fetchActivity(id);
  }

  async fetchActivity(id: string): Promise<void> {
    this.activityStatus = 'loading';
    try {
      const result = await getIncidentActivity(id);
      runInAction(() => {
        this.activity = result.content;
        this.activityStatus = 'loaded';
      });
    } catch {
      runInAction(() => {
        this.activityStatus = 'error';
      });
    }
  }

  // Only updates rows already on screen, never inserts -- an insert would ignore filters/sort/page
  // boundary. New incidents show up on the next fetch.
  applyLiveIncidentUpdate(incident: IncidentSummary): void {
    const index = this.items.findIndex((item) => item.id === incident.id);
    if (index !== -1) {
      this.items[index] = incident;
    }
    // keep version current or the next optimistic-lock write gets rejected
    if (this.detail && this.detail.id === incident.id) {
      this.detail = {
        // only summary fields copied -- don't blank description/reporter/notes, summary doesn't carry them
        ...this.detail,
        title: incident.title,
        service: incident.service,
        assignee: incident.assignee,
        priority: incident.priority,
        severity: incident.severity,
        status: incident.status,
        version: incident.version,
        updatedAt: incident.updatedAt,
        sla: { ...this.detail.sla, state: incident.slaState },
      };
      // detail view also shows audit trail/SLA timestamps a summary doesn't carry -- re-read for real
      void this.refreshOpenDetail(incident.id);
    }
  }

  // Background re-read triggered by a push. Deliberately quiet -- unlike fetchDetail it never
  // clears `detail` or shows a loading state, since flashing a spinner over valid data would make
  // someone else's status change look like a page reload.
  private async refreshOpenDetail(id: string): Promise<void> {
    const seq = (this.detailRefreshSeq += 1);
    try {
      const [detail, activity] = await Promise.all([getIncident(id), getIncidentActivity(id)]);
      runInAction(() => {
        // a newer refresh already answered, or the user moved on, while this was in flight
        if (this.detailRefreshSeq !== seq || this.detail?.id !== id) return;
        // a local mutation can land mid-flight and is newer by definition -- don't roll it back
        if (detail.version < this.detail.version) return;
        this.detail = detail;
        this.activity = activity.content;
        this.activityStatus = 'loaded';
      });
    } catch {
      // swallow -- a failed background refresh shouldn't nuke a working detail view
    }
  }

  // re-reads from the REST API since events missed while the socket was down are never replayed
  refreshAfterReconnect(): void {
    if (this.listStatus === 'loaded' || this.listStatus === 'error') {
      void this.fetchList();
    }
    if (this.detail) {
      void this.fetchDetail(this.detail.id);
    }
  }

  // refetches the list rather than inserting locally, same reason as applyLiveIncidentUpdate
  async create(request: CreateIncidentRequest): Promise<IncidentDetail | null> {
    this.mutationPending = true;
    this.mutationError = null;
    try {
      const created = await createIncident(request);
      runInAction(() => {
        this.mutationPending = false;
      });
      await this.fetchList();
      return created;
    } catch (error) {
      runInAction(() => {
        this.mutationPending = false;
        this.mutationError = mapApiErrorToKey(error);
      });
      return null;
    }
  }

  // returns success/failure; the page decides what to do with it (e.g. close a menu)
  async transition(id: string, request: TransitionIncidentRequest): Promise<boolean> {
    return this.mutate(() => transitionIncident(id, request), id);
  }

  async assign(id: string, request: AssignIncidentRequest): Promise<boolean> {
    return this.mutate(() => assignIncident(id, request), id);
  }

  private async mutate(request: () => Promise<IncidentDetail>, incidentId: string): Promise<boolean> {
    this.mutationPending = true;
    this.mutationError = null;
    try {
      const updated = await request();
      runInAction(() => {
        this.detail = updated;
        this.mutationPending = false;
      });
      void this.fetchActivity(incidentId);
      return true;
    } catch (error) {
      runInAction(() => {
        this.mutationPending = false;
        this.mutationError = mapApiErrorToKey(error);
      });
      return false;
    }
  }
}
