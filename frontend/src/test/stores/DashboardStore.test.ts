import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../api/client';

vi.mock('../../api/dashboardApi', () => ({
  getDashboardSummary: vi.fn(),
}));
vi.mock('../../api/incidentApi', () => ({
  listIncidents: vi.fn(),
}));

import { getDashboardSummary } from '../../api/dashboardApi';
import { listIncidents } from '../../api/incidentApi';
import { DashboardStore } from '../../stores/DashboardStore';
import type { DashboardSummary } from '../../types/dashboard';
import type { IncidentSummary } from '../../types/incident';

const getDashboardSummaryMock = vi.mocked(getDashboardSummary);
const listIncidentsMock = vi.mocked(listIncidents);

const RECENT: IncidentSummary = {
  id: 'inc-1',
  publicKey: 'INC-1000',
  title: 'Checkout is down',
  service: { id: 'svc-1', name: 'Checkout API' },
  assignee: null,
  priority: 'P1',
  severity: 'SEV1',
  status: 'OPEN',
  slaState: 'ON_TRACK',
  slaResolutionTargetAt: null,
  version: 0,
  createdAt: '2026-03-01T00:00:00Z',
  updatedAt: '2026-03-01T00:00:00Z',
};

const SUMMARY: DashboardSummary = {
  kpis: { openIncidents: 4, unassignedIncidents: 0, slaBreachesThisWeek: 1, mttrMinutes: 41 },
  priorityDistribution: [
    { priority: 'P1', count: 1 },
    { priority: 'P2', count: 2 },
    { priority: 'P3', count: 1 },
    { priority: 'P4', count: 0 },
  ],
  mttrTrend: [
    { date: '2026-02-23', mttrMinutes: 30 },
    { date: '2026-02-24', mttrMinutes: null },
  ],
};

describe('DashboardStore', () => {
  let store: DashboardStore;

  beforeEach(() => {
    getDashboardSummaryMock.mockReset();
    listIncidentsMock.mockReset();
    store = new DashboardStore();
  });

  describe('fetchSummary', () => {
    it('loads the summary on success and stamps a refresh time', async () => {
      getDashboardSummaryMock.mockResolvedValue(SUMMARY);

      await store.fetchSummary();

      expect(store.status).toBe('loaded');
      expect(store.summary).toEqual(SUMMARY);
      expect(store.lastRefreshedAt).toBeInstanceOf(Date);
    });

    it('sets an i18n error key on failure', async () => {
      getDashboardSummaryMock.mockRejectedValue(new ApiError(500, 'INTERNAL_ERROR', 'boom'));

      await store.fetchSummary();

      expect(store.status).toBe('error');
      expect(store.error).toBe('errors.loadFailed');
    });
  });

  describe('isEmpty', () => {
    it('is false before anything has loaded', () => {
      expect(store.isEmpty).toBe(false);
    });

    it('is false when the summary has real, non-zero values', async () => {
      getDashboardSummaryMock.mockResolvedValue(SUMMARY);
      await store.fetchSummary();

      expect(store.isEmpty).toBe(false);
    });

    it('is true only when every kpi and every priority bucket is genuinely zero/null', async () => {
      getDashboardSummaryMock.mockResolvedValue({
        kpis: { openIncidents: 0, unassignedIncidents: 0, slaBreachesThisWeek: 0, mttrMinutes: null },
        priorityDistribution: [
          { priority: 'P1', count: 0 },
          { priority: 'P2', count: 0 },
          { priority: 'P3', count: 0 },
          { priority: 'P4', count: 0 },
        ],
        mttrTrend: [],
      });

      await store.fetchSummary();

      expect(store.isEmpty).toBe(true);
    });
  });

  describe('recentIncidents', () => {
    it('loads the newest incidents through the shared list endpoint', async () => {
      listIncidentsMock.mockResolvedValue({
        content: [RECENT],
        page: 0,
        size: 7,
        totalElements: 1,
        totalPages: 1,
        first: true,
        last: true,
      });

      await store.fetchRecentIncidents();

      expect(store.recentIncidents).toEqual([RECENT]);
      expect(store.recentStatus).toBe('loaded');
      // Its own fixed page size; the endpoint already sorts createdAt DESC.
      expect(listIncidentsMock).toHaveBeenCalledWith({ size: 7 });
    });

    it('records an error without disturbing the KPIs above it', async () => {
      getDashboardSummaryMock.mockResolvedValue(SUMMARY);
      await store.fetchSummary();
      listIncidentsMock.mockRejectedValue(new ApiError(500, 'INTERNAL_ERROR', 'boom'));

      await store.fetchRecentIncidents();

      expect(store.recentStatus).toBe('error');
      expect(store.recentIncidents).toEqual([]);
      // Summary stays untouched -- one card failing must not blank the whole dashboard.
      expect(store.status).toBe('loaded');
      expect(store.summary).toEqual(SUMMARY);
    });

    it('re-reads the feed alongside the KPIs when a live event lands', async () => {
      getDashboardSummaryMock.mockResolvedValue(SUMMARY);
      listIncidentsMock.mockResolvedValue({
        content: [RECENT],
        page: 0,
        size: 7,
        totalElements: 1,
        totalPages: 1,
        first: true,
        last: true,
      });
      await store.fetchSummary();
      listIncidentsMock.mockClear();

      store.markStale();

      expect(listIncidentsMock).toHaveBeenCalledWith({ size: 7 });
    });
  });
});
