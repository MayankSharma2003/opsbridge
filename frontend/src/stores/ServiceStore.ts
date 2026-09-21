import { makeAutoObservable, runInAction } from 'mobx';
import {
  createService,
  getService,
  listServices,
  updateService,
  type ServiceSearchParams,
} from '../api/serviceApi';
import { listIncidents } from '../api/incidentApi';
import { mapApiErrorToKey } from '../api/errorMessages';
import type { CreateServiceRequest, ServiceDetail, UpdateServiceRequest } from '../types/service';
import type { IncidentSummary } from '../types/incident';
import type { FetchStatus } from './IncidentStore';

const DEFAULT_SIZE = 25;
const TERMINAL_STATUSES = new Set(['RESOLVED', 'CANCELLED']);

// Service list, filters, pagination, drawer detail, and Admin-only create/edit (spec 7.2).
// Archive stays unexposed in the UI. As everywhere else, the role gate is UX only -- the backend
// enforces ADMIN independently.
export class ServiceStore {
  items: ServiceDetail[] = [];
  totalElements = 0;
  totalPages = 0;
  page = 0;
  size = DEFAULT_SIZE;
  filters: ServiceSearchParams = {};
  listStatus: FetchStatus = 'idle';
  listError: string | null = null;

  selected: ServiceDetail | null = null;
  selectedStatus: FetchStatus = 'idle';
  selectedError: string | null = null;

  // Up to 100 incidents, for the drawer's recent-incidents list only -- the open-count and health
  // badge instead read ServiceDetail's server-computed counts, correct regardless of page size.
  relatedIncidents: IncidentSummary[] = [];
  relatedIncidentsStatus: FetchStatus = 'idle';

  mutationPending = false;
  mutationError: string | null = null;

  constructor() {
    makeAutoObservable(this);
  }

  get isListEmpty(): boolean {
    return this.listStatus === 'loaded' && this.items.length === 0;
  }

  get relatedOpenIncidents(): IncidentSummary[] {
    return this.relatedIncidents.filter((incident) => !TERMINAL_STATUSES.has(incident.status));
  }

  // refetches the list so the new service lands in the current sort/filter/page
  async create(request: CreateServiceRequest): Promise<ServiceDetail | null> {
    return this.mutate(() => createService(request));
  }

  // refreshes the drawer's copy so it can't keep showing pre-edit values
  async update(id: string, request: UpdateServiceRequest): Promise<ServiceDetail | null> {
    const updated = await this.mutate(() => updateService(id, request));
    if (updated && this.selected?.id === id) {
      runInAction(() => {
        this.selected = updated;
      });
    }
    return updated;
  }

  private async mutate(request: () => Promise<ServiceDetail>): Promise<ServiceDetail | null> {
    this.mutationPending = true;
    this.mutationError = null;
    try {
      const result = await request();
      runInAction(() => {
        this.mutationPending = false;
      });
      await this.fetchList();
      return result;
    } catch (error) {
      runInAction(() => {
        this.mutationPending = false;
        this.mutationError = mapApiErrorToKey(error);
      });
      return null;
    }
  }

  async fetchList(): Promise<void> {
    this.listStatus = 'loading';
    this.listError = null;
    try {
      const result = await listServices({ ...this.filters, page: this.page, size: this.size });
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

  setFilters(patch: Partial<ServiceSearchParams>): void {
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

  async selectService(id: string): Promise<void> {
    this.selectedStatus = 'loading';
    this.selectedError = null;
    this.selected = null;
    try {
      const detail = await getService(id);
      runInAction(() => {
        this.selected = detail;
        this.selectedStatus = 'loaded';
      });
    } catch (error) {
      runInAction(() => {
        this.selectedStatus = 'error';
        this.selectedError = mapApiErrorToKey(error, 'errors.loadFailed');
      });
      return;
    }
    void this.fetchRelatedIncidents(id);
  }

  clearSelection(): void {
    this.selected = null;
    this.selectedStatus = 'idle';
    this.relatedIncidents = [];
    this.relatedIncidentsStatus = 'idle';
  }

  private async fetchRelatedIncidents(serviceId: string): Promise<void> {
    this.relatedIncidentsStatus = 'loading';
    try {
      const result = await listIncidents({ serviceId, size: 100, sort: 'createdAt,desc' });
      runInAction(() => {
        this.relatedIncidents = result.content;
        this.relatedIncidentsStatus = 'loaded';
      });
    } catch {
      runInAction(() => {
        this.relatedIncidentsStatus = 'error';
      });
    }
  }
}
