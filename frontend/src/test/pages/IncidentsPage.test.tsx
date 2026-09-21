import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ApiError } from '../../api/client';
import '../../i18n';

vi.mock('../../api/incidentApi', () => ({
  createIncident: vi.fn(),
  listIncidents: vi.fn(),
  getIncident: vi.fn(),
  getIncidentActivity: vi.fn(),
  transitionIncident: vi.fn(),
  assignIncident: vi.fn(),
}));
vi.mock('../../api/serviceApi', () => ({
  listServices: vi.fn(),
  getService: vi.fn(),
}));

import { createIncident, listIncidents } from '../../api/incidentApi';
import { listServices } from '../../api/serviceApi';
import { IncidentsPage } from '../../pages/IncidentsPage';
import { StoreContext } from '../../stores/StoreContext';
import { RootStore } from '../../stores/RootStore';
import type { IncidentSummary } from '../../types/incident';
import type { Role } from '../../types/auth';

const listIncidentsMock = vi.mocked(listIncidents);
const createIncidentMock = vi.mocked(createIncident);
const listServicesMock = vi.mocked(listServices);

const INCIDENT: IncidentSummary = {
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

function renderPage(role: Role = 'ADMIN') {
  const rootStore = new RootStore();
  rootStore.authStore.user = { id: 'me', name: 'Dev User', email: 'me@opsbridge.local', role };
  render(
    <StoreContext.Provider value={rootStore}>
      <MemoryRouter initialEntries={['/incidents']}>
        <Routes>
          <Route path="/incidents" element={<IncidentsPage />} />
          <Route path="/incidents/:id" element={<div>Detail page</div>} />
        </Routes>
      </MemoryRouter>
    </StoreContext.Provider>,
  );
  return rootStore;
}

describe('IncidentsPage', () => {
  beforeEach(() => {
    listIncidentsMock.mockReset();
    createIncidentMock.mockReset();
    listServicesMock.mockReset();
    listServicesMock.mockResolvedValue({
      content: [{ id: 'svc-1', name: 'Checkout API' }],
      page: 0,
      size: 100,
      totalElements: 1,
      totalPages: 1,
      first: true,
      last: true,
    } as never);
  });

  it('shows the empty state when the search returns nothing', async () => {
    listIncidentsMock.mockResolvedValue({
      content: [],
      page: 0,
      size: 25,
      totalElements: 0,
      totalPages: 0,
      first: true,
      last: true,
    });

    renderPage();

    expect(await screen.findByText('Nothing matches these filters')).toBeInTheDocument();
  });

  it('shows the error state with a working retry', async () => {
    listIncidentsMock.mockRejectedValueOnce(new Error('boom'));
    listIncidentsMock.mockResolvedValue({
      content: [INCIDENT],
      page: 0,
      size: 25,
      totalElements: 1,
      totalPages: 1,
      first: true,
      last: true,
    });

    renderPage();
    expect(await screen.findByText("Couldn't load incidents")).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));

    expect(await screen.findByText('Checkout is down')).toBeInTheDocument();
  });

  it('renders incident rows and navigates to the detail page on click', async () => {
    listIncidentsMock.mockResolvedValue({
      content: [INCIDENT],
      page: 0,
      size: 25,
      totalElements: 1,
      totalPages: 1,
      first: true,
      last: true,
    });

    renderPage();
    const row = await screen.findByText('Checkout is down');
    fireEvent.click(row.closest('tr')!);

    expect(await screen.findByText('Detail page')).toBeInTheDocument();
  });

  it('applies a status filter and refetches', async () => {
    listIncidentsMock.mockResolvedValue({
      content: [INCIDENT],
      page: 0,
      size: 25,
      totalElements: 1,
      totalPages: 1,
      first: true,
      last: true,
    });

    renderPage();
    await screen.findByText('Checkout is down');
    listIncidentsMock.mockClear();

    fireEvent.click(screen.getByRole('button', { name: 'Investigating' }));

    await waitFor(() =>
      expect(listIncidentsMock).toHaveBeenCalledWith(
        expect.objectContaining({ status: 'INVESTIGATING', page: 0 }),
      ),
    );
  });

  it('renders numbered page buttons and navigates when one is clicked', async () => {
    listIncidentsMock.mockResolvedValue({
      content: [INCIDENT],
      page: 0,
      size: 25,
      totalElements: 60,
      totalPages: 3,
      first: true,
      last: false,
    });

    renderPage();
    await screen.findByText('Checkout is down');
    listIncidentsMock.mockClear();

    fireEvent.click(screen.getByRole('button', { name: '2' }));

    await waitFor(() => expect(listIncidentsMock).toHaveBeenCalledWith(expect.objectContaining({ page: 1 })));
  });

  it('marks the current page as active among the numbered buttons', async () => {
    listIncidentsMock.mockResolvedValue({
      content: [INCIDENT],
      page: 0,
      size: 25,
      totalElements: 60,
      totalPages: 3,
      first: true,
      last: false,
    });

    renderPage();
    await screen.findByText('Checkout is down');

    fireEvent.click(screen.getByRole('button', { name: '2' }));

    await waitFor(() => expect(screen.getByRole('button', { name: '2' })).toHaveAttribute('aria-current', 'page'));
    expect(screen.getByRole('button', { name: '1' })).not.toHaveAttribute('aria-current');
  });

  it('breaks the subtitle down into open and breached when the page holds every match', async () => {
    listIncidentsMock.mockResolvedValue({
      content: [
        { ...INCIDENT, id: 'a', status: 'OPEN', slaState: 'ON_TRACK' },
        { ...INCIDENT, id: 'b', status: 'INVESTIGATING', slaState: 'BREACHED' },
        { ...INCIDENT, id: 'c', status: 'RESOLVED', slaState: 'MET' },
      ],
      page: 0,
      size: 25,
      totalElements: 3,
      totalPages: 1,
      first: true,
      last: true,
    });

    renderPage();

    expect(await screen.findByText('3 matching · 2 open · 1 breached')).toBeInTheDocument();
  });

  it('shows only the total when the result set spills past the loaded page', async () => {
    listIncidentsMock.mockResolvedValue({
      content: [{ ...INCIDENT, status: 'OPEN' }],
      page: 0,
      size: 25,
      totalElements: 60,
      totalPages: 3,
      first: true,
      last: false,
    });

    renderPage();

    // "1 open" beside "60 matching" would read as 1-of-60, not 1-on-this-page.
    expect(await screen.findByText('60 matching')).toBeInTheDocument();
    expect(screen.queryByText(/open ·/)).not.toBeInTheDocument();
  });

  it('falls back to Prev/Next only when there are too many pages to number', async () => {
    listIncidentsMock.mockResolvedValue({
      content: [INCIDENT],
      page: 0,
      size: 25,
      totalElements: 400,
      totalPages: 16,
      first: true,
      last: false,
    });

    renderPage();
    await screen.findByText('Checkout is down');

    expect(screen.queryByRole('button', { name: '2' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Prev' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Next' })).toBeInTheDocument();
  });

  describe('declare incident', () => {
    const onePage = {
      content: [INCIDENT],
      page: 0,
      size: 25,
      totalElements: 1,
      totalPages: 1,
      first: true,
      last: true,
    };

    it('offers the action to an Operator', async () => {
      listIncidentsMock.mockResolvedValue(onePage);

      renderPage('OPERATOR');
      await screen.findByText('Checkout is down');

      expect(screen.getByRole('button', { name: 'Declare incident' })).toBeInTheDocument();
    });

    it('hides the action from a Viewer', async () => {
      listIncidentsMock.mockResolvedValue(onePage);

      renderPage('VIEWER');
      await screen.findByText('Checkout is down');

      // UX only -- the endpoint rejects a Viewer regardless of what renders here.
      expect(screen.queryByRole('button', { name: 'Declare incident' })).not.toBeInTheDocument();
    });

    it('refuses to submit without a title or service, without calling the API', async () => {
      listIncidentsMock.mockResolvedValue(onePage);

      renderPage('ADMIN');
      await screen.findByText('Checkout is down');
      fireEvent.click(screen.getByRole('button', { name: 'Declare incident' }));
      fireEvent.click(screen.getByRole('button', { name: 'Declare' }));

      expect(await screen.findByText('A title is required.')).toBeInTheDocument();
      expect(screen.getByText('Pick the affected service.')).toBeInTheDocument();
      expect(createIncidentMock).not.toHaveBeenCalled();
    });

    it('submits the real payload and navigates to the new incident', async () => {
      listIncidentsMock.mockResolvedValue(onePage);
      createIncidentMock.mockResolvedValue({ id: 'inc-new' } as never);

      renderPage('ADMIN');
      await screen.findByText('Checkout is down');
      fireEvent.click(screen.getByRole('button', { name: 'Declare incident' }));

      fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'Payments failing' } });
      fireEvent.change(screen.getByLabelText('Affected service'), { target: { value: 'svc-1' } });
      fireEvent.change(screen.getByLabelText('Priority'), { target: { value: 'P1' } });
      fireEvent.click(screen.getByRole('button', { name: 'Declare' }));

      await waitFor(() =>
        expect(createIncidentMock).toHaveBeenCalledWith({
          title: 'Payments failing',
          description: undefined,
          serviceId: 'svc-1',
          priority: 'P1',
          severity: 'SEV3',
        }),
      );
      expect(await screen.findByText('Detail page')).toBeInTheDocument();
    });

    it('surfaces a server rejection instead of closing the form', async () => {
      listIncidentsMock.mockResolvedValue(onePage);
      createIncidentMock.mockRejectedValue(new ApiError(403, 'FORBIDDEN', 'nope'));

      renderPage('ADMIN');
      await screen.findByText('Checkout is down');
      fireEvent.click(screen.getByRole('button', { name: 'Declare incident' }));
      fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'Payments failing' } });
      fireEvent.change(screen.getByLabelText('Affected service'), { target: { value: 'svc-1' } });
      fireEvent.click(screen.getByRole('button', { name: 'Declare' }));

      expect(await screen.findByText("Your role can't do that.")).toBeInTheDocument();
      expect(screen.getByRole('dialog')).toBeInTheDocument();
    });
  });
});
