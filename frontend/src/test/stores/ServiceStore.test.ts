import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../api/client';

vi.mock('../../api/serviceApi', () => ({
  listServices: vi.fn(),
  getService: vi.fn(),
  createService: vi.fn(),
  updateService: vi.fn(),
}));
vi.mock('../../api/incidentApi', () => ({
  listIncidents: vi.fn(),
}));

import { createService, getService, listServices, updateService } from '../../api/serviceApi';
import { listIncidents } from '../../api/incidentApi';
import { ServiceStore } from '../../stores/ServiceStore';
import type { ServiceDetail } from '../../types/service';
import type { IncidentSummary } from '../../types/incident';

const listServicesMock = vi.mocked(listServices);
const getServiceMock = vi.mocked(getService);
const listIncidentsMock = vi.mocked(listIncidents);
const createServiceMock = vi.mocked(createService);
const updateServiceMock = vi.mocked(updateService);

const SERVICE: ServiceDetail = {
  id: 'svc-1',
  name: 'Checkout API',
  description: null,
  owner: { id: 'u1', name: 'Owner', email: 'owner@opsbridge.local', role: 'ADMIN' },
  team: 'Payments',
  escalationContact: null,
  availabilityTarget: 99.9,
  status: 'ACTIVE',
  openIncidentCount: 0,
  urgentOpenIncidentCount: 0,
  createdAt: '2026-03-01T00:00:00Z',
  updatedAt: '2026-03-01T00:00:00Z',
};

function incident(status: IncidentSummary['status'], priority: IncidentSummary['priority']): IncidentSummary {
  return {
    id: `inc-${status}-${priority}`,
    publicKey: 'INC-1000',
    title: 'Incident',
    service: { id: 'svc-1', name: 'Checkout API' },
    assignee: null,
    priority,
    severity: 'SEV3',
    status,
    slaState: 'ON_TRACK',
    slaResolutionTargetAt: null,
    version: 0,
    createdAt: '2026-03-01T00:00:00Z',
    updatedAt: '2026-03-01T00:00:00Z',
  };
}

describe('ServiceStore', () => {
  let store: ServiceStore;

  beforeEach(() => {
    listServicesMock.mockReset();
    getServiceMock.mockReset();
    listIncidentsMock.mockReset();
    createServiceMock.mockReset();
    updateServiceMock.mockReset();
    listIncidentsMock.mockResolvedValue({
      content: [],
      page: 0,
      size: 100,
      totalElements: 0,
      totalPages: 0,
      first: true,
      last: true,
    });
    store = new ServiceStore();
  });

  describe('fetchList', () => {
    it('loads items on success', async () => {
      listServicesMock.mockResolvedValue({
        content: [SERVICE],
        page: 0,
        size: 25,
        totalElements: 1,
        totalPages: 1,
        first: true,
        last: true,
      });

      await store.fetchList();

      expect(store.listStatus).toBe('loaded');
      expect(store.items).toEqual([SERVICE]);
    });

    it('sets an i18n error key on failure', async () => {
      listServicesMock.mockRejectedValue(new ApiError(500, 'INTERNAL_ERROR', 'boom'));

      await store.fetchList();

      expect(store.listStatus).toBe('error');
      expect(store.listError).toBe('errors.loadFailed');
    });
  });

  describe('selectService', () => {
    it('loads the service and its related incidents', async () => {
      getServiceMock.mockResolvedValue(SERVICE);
      listIncidentsMock.mockResolvedValue({
        content: [incident('OPEN', 'P1'), incident('RESOLVED', 'P4')],
        page: 0,
        size: 100,
        totalElements: 2,
        totalPages: 1,
        first: true,
        last: true,
      });

      await store.selectService('svc-1');
      await Promise.resolve();

      expect(store.selected).toEqual(SERVICE);
      expect(store.selectedStatus).toBe('loaded');
      expect(store.relatedIncidents).toHaveLength(2);
      expect(listIncidentsMock).toHaveBeenCalledWith(
        expect.objectContaining({ serviceId: 'svc-1' }),
      );
    });

    it('excludes terminal incidents from relatedOpenIncidents', async () => {
      getServiceMock.mockResolvedValue(SERVICE);
      listIncidentsMock.mockResolvedValue({
        content: [incident('OPEN', 'P2'), incident('RESOLVED', 'P1'), incident('CANCELLED', 'P1')],
        page: 0,
        size: 100,
        totalElements: 3,
        totalPages: 1,
        first: true,
        last: true,
      });

      await store.selectService('svc-1');
      await Promise.resolve();

      expect(store.relatedOpenIncidents).toHaveLength(1);
      expect(store.relatedOpenIncidents[0].status).toBe('OPEN');
    });

    it('does not fetch related incidents when the service itself fails to load', async () => {
      getServiceMock.mockRejectedValue(new ApiError(404, 'RESOURCE_NOT_FOUND', 'not found'));

      await store.selectService('missing');

      expect(store.selectedStatus).toBe('error');
      expect(listIncidentsMock).not.toHaveBeenCalled();
    });
  });

  describe('clearSelection', () => {
    it('resets selected service and related incidents', async () => {
      getServiceMock.mockResolvedValue(SERVICE);
      listIncidentsMock.mockResolvedValue({
        content: [incident('OPEN', 'P1')],
        page: 0,
        size: 100,
        totalElements: 1,
        totalPages: 1,
        first: true,
        last: true,
      });
      await store.selectService('svc-1');
      await Promise.resolve();

      store.clearSelection();

      expect(store.selected).toBeNull();
      expect(store.selectedStatus).toBe('idle');
      expect(store.relatedIncidents).toEqual([]);
    });
  });

  describe('catalog mutations', () => {
    const page = (content: ServiceDetail[]) => ({
      content,
      page: 0,
      size: 25,
      totalElements: content.length,
      totalPages: 1,
      first: true,
      last: true,
    });

    it('creates a service and refetches the list so it lands in the current sort', async () => {
      createServiceMock.mockResolvedValue(SERVICE);
      listServicesMock.mockResolvedValue(page([SERVICE]));

      const created = await store.create({ name: 'Checkout API', ownerId: 'u1' });

      expect(created).toEqual(SERVICE);
      expect(createServiceMock).toHaveBeenCalledWith({ name: 'Checkout API', ownerId: 'u1' });
      expect(listServicesMock).toHaveBeenCalled();
      expect(store.mutationPending).toBe(false);
    });

    it('updates a service and refreshes the open drawer so it cannot show pre-edit values', async () => {
      const edited = { ...SERVICE, name: 'checkout-api', team: 'Storefront' };
      updateServiceMock.mockResolvedValue(edited);
      listServicesMock.mockResolvedValue(page([edited]));
      store.selected = SERVICE;

      const result = await store.update('svc-1', { name: 'checkout-api', team: 'Storefront' });

      expect(result).toEqual(edited);
      expect(store.selected).toEqual(edited);
    });

    it('leaves a different open drawer alone when some other service is edited', async () => {
      const other: ServiceDetail = { ...SERVICE, id: 'svc-2', name: 'identity-svc' };
      updateServiceMock.mockResolvedValue({ ...SERVICE, name: 'renamed' });
      listServicesMock.mockResolvedValue(page([]));
      store.selected = other;

      await store.update('svc-1', { name: 'renamed' });

      expect(store.selected).toEqual(other);
    });

    it('maps a rejected create to its i18n key and does not refetch', async () => {
      createServiceMock.mockRejectedValue(new ApiError(403, 'FORBIDDEN', 'admin only'));
      listServicesMock.mockClear();

      const created = await store.create({ name: 'Nope', ownerId: 'u1' });

      expect(created).toBeNull();
      expect(store.mutationError).toBe('errors.forbidden');
      expect(listServicesMock).not.toHaveBeenCalled();
    });

    it('maps a rejected update to its i18n key and leaves the drawer untouched', async () => {
      updateServiceMock.mockRejectedValue(new ApiError(400, 'VALIDATION_ERROR', 'bad'));
      store.selected = SERVICE;

      const result = await store.update('svc-1', { name: '' });

      expect(result).toBeNull();
      expect(store.mutationError).toBe('errors.validation');
      expect(store.selected).toEqual(SERVICE);
    });
  });
});
