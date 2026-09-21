import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../api/client';

vi.mock('../../api/incidentApi', () => ({
  createIncident: vi.fn(),
  listIncidents: vi.fn(),
  getIncident: vi.fn(),
  getIncidentActivity: vi.fn(),
  transitionIncident: vi.fn(),
  assignIncident: vi.fn(),
}));

import {
  assignIncident,
  createIncident,
  getIncident,
  getIncidentActivity,
  listIncidents,
  transitionIncident,
} from '../../api/incidentApi';
import { IncidentStore } from '../../stores/IncidentStore';
import type { IncidentActivityEntry, IncidentDetail, IncidentSummary } from '../../types/incident';

const createIncidentMock = vi.mocked(createIncident);
const listIncidentsMock = vi.mocked(listIncidents);
const getIncidentMock = vi.mocked(getIncident);
const getIncidentActivityMock = vi.mocked(getIncidentActivity);
const transitionIncidentMock = vi.mocked(transitionIncident);
const assignIncidentMock = vi.mocked(assignIncident);

const SUMMARY: IncidentSummary = {
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

const DETAIL: IncidentDetail = {
  id: 'inc-1',
  publicKey: 'INC-1000',
  title: 'Checkout is down',
  description: null,
  service: { id: 'svc-1', name: 'Checkout API' },
  reporter: { id: 'u1', name: 'Reporter', email: 'r@opsbridge.local', role: 'OPERATOR' },
  assignee: null,
  priority: 'P1',
  severity: 'SEV1',
  status: 'OPEN',
  sla: {
    state: 'ON_TRACK',
    startedAt: null,
    responseTargetAt: null,
    mitigationTargetAt: null,
    resolutionTargetAt: null,
    respondedAt: null,
    mitigatedAt: null,
    resolvedAt: null,
    breachedAt: null,
  },
  resolutionNotes: null,
  version: 0,
  createdAt: '2026-03-01T00:00:00Z',
  updatedAt: '2026-03-01T00:00:00Z',
};

describe('IncidentStore', () => {
  let store: IncidentStore;

  beforeEach(() => {
    listIncidentsMock.mockReset();
    createIncidentMock.mockReset();
    getIncidentMock.mockReset();
    getIncidentActivityMock.mockReset();
    transitionIncidentMock.mockReset();
    assignIncidentMock.mockReset();
    getIncidentActivityMock.mockResolvedValue({
      content: [],
      page: 0,
      size: 50,
      totalElements: 0,
      totalPages: 0,
      first: true,
      last: true,
    });
    store = new IncidentStore();
  });

  describe('fetchList', () => {
    it('loads items and pagination metadata on success', async () => {
      listIncidentsMock.mockResolvedValue({
        content: [SUMMARY],
        page: 0,
        size: 25,
        totalElements: 1,
        totalPages: 1,
        first: true,
        last: true,
      });

      await store.fetchList();

      expect(store.listStatus).toBe('loaded');
      expect(store.items).toEqual([SUMMARY]);
      expect(store.totalElements).toBe(1);
      expect(store.isListEmpty).toBe(false);
    });

    it('marks the list empty only once loaded with zero results', async () => {
      listIncidentsMock.mockResolvedValue({
        content: [],
        page: 0,
        size: 25,
        totalElements: 0,
        totalPages: 0,
        first: true,
        last: true,
      });

      expect(store.isListEmpty).toBe(false);
      await store.fetchList();

      expect(store.listStatus).toBe('loaded');
      expect(store.isListEmpty).toBe(true);
    });

    it('sets an i18n error key on failure', async () => {
      listIncidentsMock.mockRejectedValue(new ApiError(500, 'INTERNAL_ERROR', 'boom'));

      await store.fetchList();

      expect(store.listStatus).toBe('error');
      expect(store.listError).toBe('errors.loadFailed');
    });
  });

  describe('setFilters', () => {
    it('merges the patch, resets to page 0, and refetches', async () => {
      listIncidentsMock.mockResolvedValue({
        content: [],
        page: 0,
        size: 25,
        totalElements: 0,
        totalPages: 0,
        first: true,
        last: true,
      });
      store.goToPage(3);
      listIncidentsMock.mockClear();

      store.setFilters({ status: 'INVESTIGATING' });
      await Promise.resolve();

      expect(store.filters.status).toBe('INVESTIGATING');
      expect(store.page).toBe(0);
      expect(listIncidentsMock).toHaveBeenCalledWith(
        expect.objectContaining({ status: 'INVESTIGATING', page: 0 }),
      );
    });
  });

  describe('fetchDetail', () => {
    it('loads the incident and then its activity', async () => {
      getIncidentMock.mockResolvedValue(DETAIL);
      getIncidentActivityMock.mockResolvedValue({
        content: [],
        page: 0,
        size: 50,
        totalElements: 0,
        totalPages: 0,
        first: true,
        last: true,
      });

      await store.fetchDetail('inc-1');
      await Promise.resolve();

      expect(store.detail).toEqual(DETAIL);
      expect(store.detailStatus).toBe('loaded');
      expect(getIncidentActivityMock).toHaveBeenCalledWith('inc-1');
    });

    it('sets an i18n error key when the incident cannot be found', async () => {
      getIncidentMock.mockRejectedValue(new ApiError(404, 'RESOURCE_NOT_FOUND', 'not found'));

      await store.fetchDetail('missing');

      expect(store.detailStatus).toBe('error');
      expect(store.detailError).toBe('errors.notFound');
      expect(store.detail).toBeNull();
    });
  });

  describe('transition', () => {
    it('updates detail and refreshes activity on success', async () => {
      const updated = { ...DETAIL, status: 'TRIAGED' as const, version: 1 };
      transitionIncidentMock.mockResolvedValue(updated);

      const ok = await store.transition('inc-1', { expectedVersion: 0, targetStatus: 'TRIAGED' });

      expect(ok).toBe(true);
      expect(store.detail).toEqual(updated);
      expect(store.mutationPending).toBe(false);
      expect(store.mutationError).toBeNull();
    });

    it('maps a stale-version conflict to its i18n key and leaves detail untouched', async () => {
      store.detail = DETAIL;
      transitionIncidentMock.mockRejectedValue(new ApiError(409, 'STALE_VERSION', 'stale'));

      const ok = await store.transition('inc-1', { expectedVersion: 0, targetStatus: 'TRIAGED' });

      expect(ok).toBe(false);
      expect(store.mutationError).toBe('errors.staleVersion');
      expect(store.detail).toEqual(DETAIL);
    });

    it('maps an illegal-transition rejection to its i18n key', async () => {
      transitionIncidentMock.mockRejectedValue(
        new ApiError(422, 'ILLEGAL_STATUS_TRANSITION', 'illegal'),
      );

      const ok = await store.transition('inc-1', { expectedVersion: 0, targetStatus: 'RESOLVED' });

      expect(ok).toBe(false);
      expect(store.mutationError).toBe('errors.illegalTransition');
    });
  });

  describe('assign', () => {
    it('updates detail on success', async () => {
      const updated = { ...DETAIL, assignee: { id: 'u2', name: 'Op', email: 'op@opsbridge.local', role: 'OPERATOR' as const } };
      assignIncidentMock.mockResolvedValue(updated);

      const ok = await store.assign('inc-1', { expectedVersion: 0, assigneeId: 'u2' });

      expect(ok).toBe(true);
      expect(store.detail?.assignee?.id).toBe('u2');
    });

    it('maps a 403 to the forbidden i18n key', async () => {
      assignIncidentMock.mockRejectedValue(new ApiError(403, 'FORBIDDEN', 'nope'));

      const ok = await store.assign('inc-1', { expectedVersion: 0, assigneeId: 'u2' });

      expect(ok).toBe(false);
      expect(store.mutationError).toBe('errors.forbidden');
    });
  });

  describe('create', () => {
    it('declares an incident and refetches the list so it lands in the right place', async () => {
      createIncidentMock.mockResolvedValue(DETAIL);
      listIncidentsMock.mockResolvedValue({
        content: [SUMMARY],
        page: 0,
        size: 25,
        totalElements: 1,
        totalPages: 1,
        first: true,
        last: true,
      });

      const created = await store.create({
        title: 'Checkout is down',
        serviceId: 'svc-1',
        priority: 'P1',
        severity: 'SEV1',
      });

      expect(created).toEqual(DETAIL);
      expect(createIncidentMock).toHaveBeenCalledWith({
        title: 'Checkout is down',
        serviceId: 'svc-1',
        priority: 'P1',
        severity: 'SEV1',
      });
      // Refetched rather than inserted locally, so active filters/sort/page still decide placement.
      expect(listIncidentsMock).toHaveBeenCalled();
      expect(store.mutationPending).toBe(false);
    });

    it('maps a validation rejection to its i18n key and returns null', async () => {
      createIncidentMock.mockRejectedValue(new ApiError(400, 'VALIDATION_ERROR', 'bad'));

      const created = await store.create({
        title: '',
        serviceId: 'svc-1',
        priority: 'P1',
        severity: 'SEV1',
      });

      expect(created).toBeNull();
      expect(store.mutationError).toBe('errors.validation');
      expect(store.mutationPending).toBe(false);
      expect(listIncidentsMock).not.toHaveBeenCalled();
    });

    it('maps a 403 to the forbidden key, since a Viewer could still reach the endpoint', async () => {
      createIncidentMock.mockRejectedValue(new ApiError(403, 'FORBIDDEN', 'nope'));

      expect(
        await store.create({ title: 'x', serviceId: 'svc-1', priority: 'P3', severity: 'SEV3' }),
      ).toBeNull();
      expect(store.mutationError).toBe('errors.forbidden');
    });
  });

  describe('list subtitle counts', () => {
    const pageOf = (content: IncidentSummary[], totalElements = content.length) => ({
      content,
      page: 0,
      size: 25,
      totalElements,
      totalPages: Math.max(1, Math.ceil(totalElements / 25)),
      first: true,
      last: totalElements <= 25,
    });

    it('counts open and breached rows out of the loaded page', async () => {
      listIncidentsMock.mockResolvedValue(
        pageOf([
          { ...SUMMARY, id: 'a', status: 'OPEN', slaState: 'ON_TRACK' },
          { ...SUMMARY, id: 'b', status: 'INVESTIGATING', slaState: 'BREACHED' },
          { ...SUMMARY, id: 'c', status: 'RESOLVED', slaState: 'MET' },
          { ...SUMMARY, id: 'd', status: 'CANCELLED', slaState: 'MET' },
        ]),
      );

      await store.fetchList();

      // Open means non-terminal, the same definition the dashboard's KPI uses.
      expect(store.openCount).toBe(2);
      expect(store.breachedCount).toBe(1);
    });

    it('reports zero counts for an empty list rather than throwing', async () => {
      listIncidentsMock.mockResolvedValue(pageOf([]));

      await store.fetchList();

      expect(store.openCount).toBe(0);
      expect(store.breachedCount).toBe(0);
    });

    it('knows when the loaded page is the whole result set', async () => {
      listIncidentsMock.mockResolvedValue(pageOf([{ ...SUMMARY }], 1));

      await store.fetchList();

      expect(store.isSinglePage).toBe(true);
    });

    it('knows when it is holding only part of the result set', async () => {
      listIncidentsMock.mockResolvedValue(pageOf([{ ...SUMMARY }], 60));

      await store.fetchList();

      // 1 of 60 matching rows in hand -- page-scoped counts must not be quoted as totals.
      expect(store.isSinglePage).toBe(false);
    });
  });

  describe('applyLiveIncidentUpdate', () => {
    const activityPage = (content: IncidentActivityEntry[]) => ({
      content,
      page: 0,
      size: 50,
      totalElements: content.length,
      totalPages: 1,
      first: true,
      last: true,
    });

    const STATUS_CHANGED: IncidentActivityEntry = {
      id: 'a2',
      actor: { id: 'u9', name: 'Other Operator', email: 'other@opsbridge.local', role: 'OPERATOR' },
      eventType: 'STATUS_CHANGED',
      fieldName: 'status',
      fromValue: 'OPEN',
      toValue: 'INVESTIGATING',
      comment: null,
      createdAt: '2026-03-01T00:05:00Z',
    };

    /** Lets every microtask queued by the background re-read settle. */
    const settle = () => vi.waitFor(() => expect(getIncidentMock).toHaveBeenCalled());

    beforeEach(() => {
      store.detail = { ...DETAIL };
      store.detailStatus = 'loaded';
      store.activityStatus = 'loaded';
      getIncidentMock.mockResolvedValue({ ...DETAIL, status: 'INVESTIGATING', version: 3 });
    });

    it('merges the push into an open detail view for the same incident', () => {
      store.applyLiveIncidentUpdate({ ...SUMMARY, status: 'INVESTIGATING', version: 3, slaState: 'AT_RISK' });

      // Synchronously, before any re-read: the badge must not wait on a round trip.
      expect(store.detail?.status).toBe('INVESTIGATING');
      expect(store.detail?.version).toBe(3);
      expect(store.detail?.sla.state).toBe('AT_RISK');
    });

    it('leaves a different incident open on screen untouched', () => {
      store.applyLiveIncidentUpdate({ ...SUMMARY, id: 'inc-2', status: 'RESOLVED' });

      expect(store.detail).toEqual(DETAIL);
      expect(getIncidentMock).not.toHaveBeenCalled();
    });

    it('does not blank the fields a summary does not carry', () => {
      store.detail = { ...DETAIL, description: 'Refresh calls began returning 401.', resolutionNotes: 'n/a' };

      store.applyLiveIncidentUpdate({ ...SUMMARY, status: 'INVESTIGATING' });

      expect(store.detail?.description).toBe('Refresh calls began returning 401.');
      expect(store.detail?.resolutionNotes).toBe('n/a');
      expect(store.detail?.reporter).toEqual(DETAIL.reporter);
    });

    it('re-reads the record and the audit trail, which no summary carries', async () => {
      getIncidentMock.mockResolvedValue({
        ...DETAIL,
        status: 'INVESTIGATING',
        version: 3,
        sla: { ...DETAIL.sla, respondedAt: '2026-03-01T00:05:00Z' },
      });
      getIncidentActivityMock.mockResolvedValue(activityPage([STATUS_CHANGED]));

      store.applyLiveIncidentUpdate({ ...SUMMARY, status: 'INVESTIGATING', version: 3 });
      await settle();

      // The timeline is the point -- it's on screen, and a status change always appends to it.
      await vi.waitFor(() => expect(store.activity).toEqual([STATUS_CHANGED]));
      expect(store.detail?.sla.respondedAt).toBe('2026-03-01T00:05:00Z');
    });

    it('never flashes a loading state over a view that is already showing data', async () => {
      store.applyLiveIncidentUpdate({ ...SUMMARY, status: 'INVESTIGATING', version: 3 });

      // fetchDetail would have nulled detail and flipped both statuses to loading by now.
      expect(store.detailStatus).toBe('loaded');
      expect(store.activityStatus).toBe('loaded');
      expect(store.detail).not.toBeNull();

      await settle();
      expect(store.detailStatus).toBe('loaded');
      expect(store.activityStatus).toBe('loaded');
    });

    it('keeps the merged values when the background re-read fails', async () => {
      getIncidentMock.mockRejectedValue(new ApiError(503, 'UNAVAILABLE', 'down'));

      store.applyLiveIncidentUpdate({ ...SUMMARY, status: 'INVESTIGATING', version: 3 });
      await settle();

      // Newer than what was on screen before the push, and still a working view.
      expect(store.detail?.status).toBe('INVESTIGATING');
      expect(store.detailStatus).toBe('loaded');
    });

    it('does not roll the screen back when a local mutation lands mid-re-read', async () => {
      let answerRead!: (detail: IncidentDetail) => void;
      getIncidentMock.mockReturnValue(new Promise<IncidentDetail>((resolve) => { answerRead = resolve; }));

      store.applyLiveIncidentUpdate({ ...SUMMARY, status: 'INVESTIGATING', version: 3 });

      // Operator acts on the same incident before the background read returns.
      transitionIncidentMock.mockResolvedValue({ ...DETAIL, status: 'MITIGATED', version: 7 });
      await store.transition('inc-1', { expectedVersion: 3, targetStatus: 'MITIGATED' });

      answerRead({ ...DETAIL, status: 'INVESTIGATING', version: 3 });
      await vi.waitFor(() => expect(store.detail?.version).toBe(7));

      expect(store.detail?.status).toBe('MITIGATED');
    });

    it('lets the newest re-read win when two pushes overlap', async () => {
      let answerFirst!: (detail: IncidentDetail) => void;
      getIncidentMock
        .mockReturnValueOnce(new Promise<IncidentDetail>((resolve) => { answerFirst = resolve; }))
        .mockResolvedValueOnce({ ...DETAIL, status: 'MITIGATED', version: 5 });

      store.applyLiveIncidentUpdate({ ...SUMMARY, status: 'INVESTIGATING', version: 4 });
      store.applyLiveIncidentUpdate({ ...SUMMARY, status: 'MITIGATED', version: 5 });
      await vi.waitFor(() => expect(store.detail?.status).toBe('MITIGATED'));

      // First read finally answers, with what the server said a version ago.
      answerFirst({ ...DETAIL, status: 'INVESTIGATING', version: 4 });
      await vi.waitFor(() => expect(getIncidentMock).toHaveBeenCalledTimes(2));

      expect(store.detail?.status).toBe('MITIGATED');
      expect(store.detail?.version).toBe(5);
    });
  });
});
