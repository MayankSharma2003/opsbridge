import { describe, expect, it, vi, beforeEach } from 'vitest';
import { act, render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import '../../i18n';

vi.mock('../../api/incidentApi', () => ({
  listIncidents: vi.fn(),
  getIncident: vi.fn(),
  getIncidentActivity: vi.fn(),
  transitionIncident: vi.fn(),
  assignIncident: vi.fn(),
}));
vi.mock('../../api/userApi', () => ({
  listUsers: vi.fn(),
}));
vi.mock('../../api/serviceApi', () => ({
  listServices: vi.fn(),
  getService: vi.fn(),
}));

import {
  assignIncident,
  getIncident,
  getIncidentActivity,
  transitionIncident,
} from '../../api/incidentApi';
import { listUsers } from '../../api/userApi';
import { getService } from '../../api/serviceApi';
import { ApiError } from '../../api/client';
import { IncidentDetailPage } from '../../pages/IncidentDetailPage';
import { StoreContext } from '../../stores/StoreContext';
import { RootStore } from '../../stores/RootStore';
import type { IncidentDetail, IncidentSummary } from '../../types/incident';
import type { Role } from '../../types/auth';
import detailStyles from '../../pages/IncidentDetailPage.module.css';
import badgeStyles from '../../components/badges.module.css';

const getIncidentMock = vi.mocked(getIncident);
const getIncidentActivityMock = vi.mocked(getIncidentActivity);
const transitionIncidentMock = vi.mocked(transitionIncident);
const assignIncidentMock = vi.mocked(assignIncident);
const listUsersMock = vi.mocked(listUsers);
const getServiceMock = vi.mocked(getService);

const DETAIL: IncidentDetail = {
  id: 'inc-1',
  publicKey: 'INC-2039',
  title: 'Auth token refresh failures',
  description: 'Refresh calls began returning 401.',
  service: { id: 'svc-1', name: 'identity-svc' },
  reporter: { id: 'u1', name: 'Reporter', email: 'r@opsbridge.local', role: 'OPERATOR' },
  assignee: null,
  priority: 'P1',
  severity: 'SEV1',
  status: 'TRIAGED',
  sla: {
    state: 'ON_TRACK',
    startedAt: '2026-03-01T00:00:00Z',
    responseTargetAt: null,
    mitigationTargetAt: null,
    resolutionTargetAt: null,
    respondedAt: '2026-03-01T00:01:00Z',
    mitigatedAt: null,
    resolvedAt: null,
    breachedAt: null,
  },
  resolutionNotes: null,
  version: 0,
  createdAt: '2026-03-01T00:00:00Z',
  updatedAt: '2026-03-01T00:00:00Z',
};

const SUMMARY: IncidentSummary = {
  id: DETAIL.id,
  publicKey: DETAIL.publicKey,
  title: DETAIL.title,
  service: DETAIL.service,
  assignee: null,
  priority: 'P1',
  severity: 'SEV1',
  status: 'TRIAGED',
  slaState: 'ON_TRACK',
  slaResolutionTargetAt: null,
  version: 0,
  createdAt: DETAIL.createdAt,
  updatedAt: DETAIL.updatedAt,
};

function renderPage(role: Role) {
  const rootStore = new RootStore();
  rootStore.authStore.user = { id: 'me', name: 'Dev User', email: 'me@opsbridge.local', role };
  render(
    <StoreContext.Provider value={rootStore}>
      <MemoryRouter initialEntries={['/incidents/inc-1']}>
        <Routes>
          <Route path="/incidents/:id" element={<IncidentDetailPage />} />
          <Route path="/incidents" element={<div>List page</div>} />
        </Routes>
      </MemoryRouter>
    </StoreContext.Provider>,
  );
  return rootStore;
}

describe('IncidentDetailPage', () => {
  beforeEach(() => {
    getIncidentMock.mockReset();
    getIncidentActivityMock.mockReset();
    transitionIncidentMock.mockReset();
    assignIncidentMock.mockReset();
    listUsersMock.mockReset();
    getServiceMock.mockReset();

    getIncidentActivityMock.mockResolvedValue({
      content: [
        {
          id: 'a1',
          actor: DETAIL.reporter,
          eventType: 'CREATED',
          fieldName: null,
          fromValue: null,
          toValue: 'OPEN',
          comment: null,
          createdAt: '2026-03-01T00:00:00Z',
        },
      ],
      page: 0,
      size: 50,
      totalElements: 1,
      totalPages: 1,
      first: true,
      last: true,
    });
    listUsersMock.mockResolvedValue({
      content: [{ id: 'op-2', name: 'M. Okafor', email: 'okafor@opsbridge.local', role: 'OPERATOR' }],
      page: 0,
      size: 100,
      totalElements: 1,
      totalPages: 1,
      first: true,
      last: true,
    });
    getServiceMock.mockResolvedValue({
      id: 'svc-1',
      name: 'identity-svc',
      description: null,
      owner: { id: 'u1', name: 'Owner', email: 'owner@opsbridge.local', role: 'ADMIN' },
      team: 'Gatekeeper',
      escalationContact: null,
      availabilityTarget: null,
      status: 'ACTIVE',
      openIncidentCount: 0,
      urgentOpenIncidentCount: 0,
      createdAt: '2026-03-01T00:00:00Z',
      updatedAt: '2026-03-01T00:00:00Z',
    });
  });

  it('shows the not-found state when the incident fails to load', async () => {
    getIncidentMock.mockRejectedValue(new Error('not found'));

    renderPage('VIEWER');

    expect(await screen.findByText('Incident not found')).toBeInTheDocument();
  });

  it('renders the incident summary, description, and activity timeline', async () => {
    getIncidentMock.mockResolvedValue(DETAIL);

    renderPage('OPERATOR');

    expect(await screen.findByRole('heading', { name: 'Auth token refresh failures' })).toBeInTheDocument();
    expect(screen.getByText('Refresh calls began returning 401.')).toBeInTheDocument();
    expect(await screen.findByText('Reporter declared this incident')).toBeInTheDocument();
  });

  it('disables status/assign controls and shows the banner for a Viewer', async () => {
    getIncidentMock.mockResolvedValue(DETAIL);

    renderPage('VIEWER');
    await screen.findByRole('heading', { name: 'Auth token refresh failures' });

    expect(screen.getByRole('button', { name: /Change status/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: /Assign/ })).toBeDisabled();
    expect(screen.getByText(/Viewer access/)).toBeInTheDocument();
  });

  it('lets an Operator open the status menu and submit a legal transition', async () => {
    getIncidentMock.mockResolvedValue(DETAIL);
    transitionIncidentMock.mockResolvedValue({ ...DETAIL, status: 'INVESTIGATING', version: 1 });

    renderPage('OPERATOR');
    await screen.findByRole('heading', { name: 'Auth token refresh failures' });

    fireEvent.click(screen.getByRole('button', { name: /Change status/ }));
    // TRIAGED's legal targets are INVESTIGATING and CANCELLED (spec 4.1's state diagram).
    fireEvent.click(screen.getByRole('button', { name: 'Investigating' }));

    await waitFor(() =>
      expect(transitionIncidentMock).toHaveBeenCalledWith('inc-1', {
        expectedVersion: 0,
        targetStatus: 'INVESTIGATING',
      }),
    );
  });

  it('opens a resolution-notes form instead of submitting immediately when Resolved is picked', async () => {
    getIncidentMock.mockResolvedValue({ ...DETAIL, status: 'MITIGATED' });
    transitionIncidentMock.mockResolvedValue({ ...DETAIL, status: 'RESOLVED', version: 1 });

    renderPage('OPERATOR');
    await screen.findByRole('heading', { name: 'Auth token refresh failures' });

    fireEvent.click(screen.getByRole('button', { name: /Change status/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Resolved' }));

    const textarea = await screen.findByPlaceholderText('What fixed it? (optional)');
    fireEvent.change(textarea, { target: { value: 'Rolled back deploy 42.' } });
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }));

    await waitFor(() =>
      expect(transitionIncidentMock).toHaveBeenCalledWith('inc-1', {
        expectedVersion: 0,
        targetStatus: 'RESOLVED',
        resolutionNotes: 'Rolled back deploy 42.',
      }),
    );
  });

  it('lets an Operator assign the incident from the real user directory', async () => {
    getIncidentMock.mockResolvedValue(DETAIL);
    assignIncidentMock.mockResolvedValue({
      ...DETAIL,
      assignee: { id: 'op-2', name: 'M. Okafor', email: 'okafor@opsbridge.local', role: 'OPERATOR' },
      version: 1,
    });

    renderPage('OPERATOR');
    await screen.findByRole('heading', { name: 'Auth token refresh failures' });

    fireEvent.click(screen.getByRole('button', { name: /Assign/ }));
    const person = await screen.findByText('M. Okafor');
    fireEvent.click(person.closest('button')!);

    await waitFor(() =>
      expect(assignIncidentMock).toHaveBeenCalledWith('inc-1', { expectedVersion: 0, assigneeId: 'op-2' }),
    );
  });

  it('shows the mapped error message when a mutation is rejected', async () => {
    getIncidentMock.mockResolvedValue(DETAIL);
    transitionIncidentMock.mockRejectedValue(new ApiError(409, 'STALE_VERSION', 'stale'));

    renderPage('OPERATOR');
    await screen.findByRole('heading', { name: 'Auth token refresh failures' });

    fireEvent.click(screen.getByRole('button', { name: /Change status/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Investigating' }));

    expect(await screen.findByText('This was changed by someone else. Reload and try again.')).toBeInTheDocument();
  });

  it('reflects a status change pushed from another browser, with no manual refresh', async () => {
    getIncidentMock.mockResolvedValue(DETAIL);

    const rootStore = renderPage('OPERATOR');
    await screen.findByRole('heading', { name: 'Auth token refresh failures' });
    expect(screen.getByText('Triaged')).toBeInTheDocument();

    // What the other browser's push and a subsequent re-read would return.
    getIncidentMock.mockResolvedValue({ ...DETAIL, status: 'INVESTIGATING', version: 1 });
    getIncidentActivityMock.mockResolvedValue({
      content: [
        {
          id: 'a2',
          actor: { id: 'u9', name: 'Other Operator', email: 'other@opsbridge.local', role: 'OPERATOR' },
          eventType: 'STATUS_CHANGED',
          fieldName: 'status',
          fromValue: 'TRIAGED',
          toValue: 'INVESTIGATING',
          comment: null,
          createdAt: '2026-03-01T00:05:00Z',
        },
      ],
      page: 0,
      size: 50,
      totalElements: 1,
      totalPages: 1,
      first: true,
      last: true,
    });

    act(() => {
      rootStore.liveStore.handleEvent({
        type: 'INCIDENT_UPDATED',
        occurredAt: '2026-03-01T00:05:00Z',
        incident: { ...SUMMARY, status: 'INVESTIGATING', version: 1 },
      });
    });

    expect(await screen.findByText('Investigating')).toBeInTheDocument();
    // Audit trail catches up too, rather than sitting a change behind.
    expect(await screen.findByText(/Other Operator/)).toBeInTheDocument();
    // Screen never blanked back to a loading state to get there.
    expect(screen.queryByText('Loading…')).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Auth token refresh failures' })).toBeInTheDocument();
  });

  it('colors each activity-timeline dot by its event category instead of one flat color', async () => {
    getIncidentMock.mockResolvedValue(DETAIL);
    getIncidentActivityMock.mockResolvedValue({
      content: [
        {
          id: 'a1',
          actor: DETAIL.reporter,
          eventType: 'CREATED',
          fieldName: null,
          fromValue: null,
          toValue: null,
          comment: null,
          createdAt: '2026-03-01T00:00:00Z',
        },
        {
          id: 'a2',
          actor: DETAIL.reporter,
          eventType: 'ASSIGNED',
          fieldName: null,
          fromValue: null,
          toValue: 'operator@opsbridge.local',
          comment: null,
          createdAt: '2026-03-01T00:01:00Z',
        },
      ],
      page: 0,
      size: 50,
      totalElements: 2,
      totalPages: 1,
      first: true,
      last: true,
    });

    renderPage('OPERATOR');
    await screen.findByRole('heading', { name: 'Auth token refresh failures' });
    // Newest first, per the timeline's own ordering.
    await screen.findByText('Reporter assigned to operator@opsbridge.local');

    const dots = document.querySelectorAll(`.${detailStyles.eventDot}`);
    expect(dots).toHaveLength(2);
    expect(dots[0].className).toContain(badgeStyles['dot-info']); // ASSIGNED
    expect(dots[1].className).toContain(badgeStyles['dot-crit']); // CREATED
  });

  it('computes the SLA Remaining bar fill from real elapsed time instead of a fixed width', async () => {
    // Only Date is faked -- setTimeout stays real so Testing Library's polling (findBy*) still works;
    // the component's clock freezes since it always calls new Date().
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-03-01T00:30:00Z'));
    try {
      getIncidentMock.mockResolvedValue({
        ...DETAIL,
        sla: {
          ...DETAIL.sla,
          state: 'ON_TRACK',
          startedAt: '2026-03-01T00:00:00Z',
          respondedAt: '2026-03-01T00:01:00Z',
          mitigationTargetAt: '2026-03-01T01:00:00Z',
        },
      });

      renderPage('OPERATOR');
      await screen.findByRole('heading', { name: 'Auth token refresh failures' });

      // 30 minutes into a 00:00-01:00 mitigation window is exactly halfway.
      const fill = document.querySelector(`.${detailStyles.slaBarFill}`) as HTMLElement;
      expect(fill.style.width).toBe('50%');
    } finally {
      vi.useRealTimers();
    }
  });
});
