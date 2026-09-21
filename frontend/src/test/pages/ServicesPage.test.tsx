import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import '../../i18n';

vi.mock('../../api/serviceApi', () => ({
  listServices: vi.fn(),
  getService: vi.fn(),
  createService: vi.fn(),
  updateService: vi.fn(),
}));
vi.mock('../../api/userApi', () => ({
  listUsers: vi.fn(),
}));
vi.mock('../../api/incidentApi', () => ({
  listIncidents: vi.fn(),
}));

import { createService, getService, listServices, updateService } from '../../api/serviceApi';
import { listUsers } from '../../api/userApi';
import { listIncidents } from '../../api/incidentApi';
import { ServicesPage } from '../../pages/ServicesPage';
import { StoreContext } from '../../stores/StoreContext';
import { RootStore } from '../../stores/RootStore';
import type { ServiceDetail } from '../../types/service';
import type { Role } from '../../types/auth';
import badgeStyles from '../../components/badges.module.css';
import servicesStyles from '../../pages/ServicesPage.module.css';

const listServicesMock = vi.mocked(listServices);
const getServiceMock = vi.mocked(getService);
const listIncidentsMock = vi.mocked(listIncidents);
const createServiceMock = vi.mocked(createService);
const updateServiceMock = vi.mocked(updateService);
const listUsersMock = vi.mocked(listUsers);

const SERVICE: ServiceDetail = {
  id: 'svc-1',
  name: 'Checkout API',
  description: 'Handles checkout',
  owner: { id: 'u1', name: 'Owner', email: 'owner@opsbridge.local', role: 'ADMIN' },
  team: 'Payments',
  escalationContact: 'oncall@opsbridge.local',
  availabilityTarget: 99.9,
  status: 'ACTIVE',
  openIncidentCount: 0,
  urgentOpenIncidentCount: 0,
  createdAt: '2026-03-01T00:00:00Z',
  updatedAt: '2026-03-01T00:00:00Z',
};

function page(content: ServiceDetail[]) {
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

function renderPage(initialEntry = '/services', role: Role = 'ADMIN') {
  const rootStore = new RootStore();
  rootStore.authStore.user = { id: 'me', name: 'Dev User', email: 'me@opsbridge.local', role };
  render(
    <StoreContext.Provider value={rootStore}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <Routes>
          <Route path="/services" element={<ServicesPage />} />
        </Routes>
      </MemoryRouter>
    </StoreContext.Provider>,
  );
  return rootStore;
}

describe('ServicesPage', () => {
  beforeEach(() => {
    listServicesMock.mockReset();
    getServiceMock.mockReset();
    listIncidentsMock.mockReset();
    createServiceMock.mockReset();
    updateServiceMock.mockReset();
    listUsersMock.mockReset();
    listUsersMock.mockResolvedValue({
      content: [{ id: 'u1', name: 'Owner', email: 'owner@opsbridge.local', role: 'ADMIN' }],
      page: 0,
      size: 100,
      totalElements: 1,
      totalPages: 1,
      first: true,
      last: true,
    });
    listIncidentsMock.mockResolvedValue({
      content: [],
      page: 0,
      size: 100,
      totalElements: 0,
      totalPages: 0,
      first: true,
      last: true,
    });
  });

  it('shows the empty state when the search returns nothing', async () => {
    listServicesMock.mockResolvedValue({
      content: [],
      page: 0,
      size: 25,
      totalElements: 0,
      totalPages: 0,
      first: true,
      last: true,
    });

    renderPage();

    expect(await screen.findByText('No services match these filters')).toBeInTheDocument();
  });

  it('shows the error state', async () => {
    listServicesMock.mockRejectedValue(new Error('boom'));

    renderPage();

    expect(await screen.findByText("Couldn't load the service catalog")).toBeInTheDocument();
  });

  it('opens the drawer with real service data when a row is clicked', async () => {
    listServicesMock.mockResolvedValue({
      content: [SERVICE],
      page: 0,
      size: 25,
      totalElements: 1,
      totalPages: 1,
      first: true,
      last: true,
    });
    getServiceMock.mockResolvedValue(SERVICE);

    renderPage();
    const row = await screen.findByText('Checkout API', { selector: 'span' });
    fireEvent.click(row.closest('tr')!);

    expect(await screen.findByText('Handles checkout')).toBeInTheDocument();
    expect(screen.getByText('oncall@opsbridge.local')).toBeInTheDocument();
  });

  it('opens the drawer directly from an ?open= query param (the "Open service page" link case)', async () => {
    listServicesMock.mockResolvedValue({
      content: [SERVICE],
      page: 0,
      size: 25,
      totalElements: 1,
      totalPages: 1,
      first: true,
      last: true,
    });
    getServiceMock.mockResolvedValue(SERVICE);

    renderPage('/services?open=svc-1');

    expect(await screen.findByText('Handles checkout')).toBeInTheDocument();
    expect(getServiceMock).toHaveBeenCalledWith('svc-1');
  });

  it('closes the drawer and clears selection', async () => {
    listServicesMock.mockResolvedValue({
      content: [SERVICE],
      page: 0,
      size: 25,
      totalElements: 1,
      totalPages: 1,
      first: true,
      last: true,
    });
    getServiceMock.mockResolvedValue(SERVICE);

    const rootStore = renderPage('/services?open=svc-1');
    await screen.findByText('Handles checkout');

    // Two "Close" affordances exist by design -- the drawer's icon and the footer button.
    fireEvent.click(screen.getAllByRole('button', { name: 'Close' })[0]);

    expect(rootStore.serviceStore.selected).toBeNull();
  });

  it('colors the drawer badges by real status/health, not a flat unstyled chip', async () => {
    // Urgent incident count comes from the server, not derived from a fetched page.
    const critical = { ...SERVICE, openIncidentCount: 3, urgentOpenIncidentCount: 1 };
    listServicesMock.mockResolvedValue(page([critical]));
    getServiceMock.mockResolvedValue(critical);

    renderPage();
    fireEvent.click((await screen.findByText('Checkout API', { selector: 'span' })).closest('tr')!);
    await screen.findByText('Handles checkout');

    const badges = document.querySelectorAll(`.${servicesStyles.drawerBadges} .${badgeStyles.pill}`);
    expect(badges).toHaveLength(3);
    expect(badges[0].className).toContain(badgeStyles.ok); // status: Active
    expect(badges[1].className).toContain(badgeStyles.crit); // health: a P1/P2 makes it critical
    expect(badges[2].className).toContain(badgeStyles.neutral); // open-incident count
  });

  it('colors the health badge ok when the service has no open incidents', async () => {
    listServicesMock.mockResolvedValue(page([SERVICE]));
    getServiceMock.mockResolvedValue(SERVICE);

    renderPage();
    fireEvent.click((await screen.findByText('Checkout API', { selector: 'span' })).closest('tr')!);
    await screen.findByText('Handles checkout');

    const badges = document.querySelectorAll(`.${servicesStyles.drawerBadges} .${badgeStyles.pill}`);
    expect(badges[1].className).toContain(badgeStyles.ok);
  });

  it('shows health and active-incident columns from the server counts', async () => {
    listServicesMock.mockResolvedValue(
      page([
        { ...SERVICE, id: 'svc-1', name: 'Checkout API', openIncidentCount: 3, urgentOpenIncidentCount: 1 },
        { ...SERVICE, id: 'svc-2', name: 'reporting-svc', openIncidentCount: 2, urgentOpenIncidentCount: 0 },
        { ...SERVICE, id: 'svc-3', name: 'quiet-svc', openIncidentCount: 0, urgentOpenIncidentCount: 0 },
      ]),
    );

    renderPage();
    await screen.findByText('Checkout API', { selector: 'span' });

    expect(screen.getByRole('columnheader', { name: 'Health' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'Active incidents' })).toBeInTheDocument();
    // A P1/P2 open is critical; open-but-routine is watching; nothing open is nominal.
    expect(screen.getByText('Critical')).toBeInTheDocument();
    expect(screen.getByText('Watching')).toBeInTheDocument();
    expect(screen.getByText('Nominal')).toBeInTheDocument();

    const rows = screen.getAllByRole('row').slice(1);
    expect(rows[0]).toHaveTextContent('3');
    expect(rows[2]).toHaveTextContent('0');
  });

  it('shows the same health in the table and the drawer for one service', async () => {
    const critical = { ...SERVICE, openIncidentCount: 4, urgentOpenIncidentCount: 2 };
    listServicesMock.mockResolvedValue(page([critical]));
    getServiceMock.mockResolvedValue(critical);

    renderPage();
    fireEvent.click((await screen.findByText('Checkout API', { selector: 'span' })).closest('tr')!);
    await screen.findByText('Handles checkout');

    // Both read the same server numbers, so row and drawer can't disagree.
    expect(screen.getAllByText('Critical')).toHaveLength(2);
  });

  describe('catalog management', () => {
    it('offers add and edit to an Admin', async () => {
      listServicesMock.mockResolvedValue(page([SERVICE]));
      getServiceMock.mockResolvedValue(SERVICE);

      renderPage('/services?open=svc-1', 'ADMIN');
      await screen.findByText('Handles checkout');

      expect(screen.getByRole('button', { name: '+ Add service' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Edit service' })).toBeInTheDocument();
    });

    it('hides both from an Operator, who is explicitly not a catalog admin', async () => {
      listServicesMock.mockResolvedValue(page([SERVICE]));
      getServiceMock.mockResolvedValue(SERVICE);

      renderPage('/services?open=svc-1', 'OPERATOR');
      await screen.findByText('Handles checkout');

      // UX only -- the catalog endpoints enforce ADMIN regardless.
      expect(screen.queryByRole('button', { name: '+ Add service' })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Edit service' })).not.toBeInTheDocument();
    });

    it('refuses to create without a name or owner, without calling the API', async () => {
      listServicesMock.mockResolvedValue(page([SERVICE]));

      renderPage('/services', 'ADMIN');
      await screen.findByText('Checkout API', { selector: 'span' });
      fireEvent.click(screen.getByRole('button', { name: '+ Add service' }));
      fireEvent.click(screen.getByRole('button', { name: 'Save' }));

      expect(await screen.findByText('A name is required.')).toBeInTheDocument();
      expect(screen.getByText('Pick an owner.')).toBeInTheDocument();
      expect(createServiceMock).not.toHaveBeenCalled();
    });

    it('submits a real create payload', async () => {
      listServicesMock.mockResolvedValue(page([SERVICE]));
      createServiceMock.mockResolvedValue(SERVICE);

      renderPage('/services', 'ADMIN');
      await screen.findByText('Checkout API', { selector: 'span' });
      fireEvent.click(screen.getByRole('button', { name: '+ Add service' }));

      fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'reporting-api' } });
      fireEvent.change(await screen.findByLabelText('Owner'), { target: { value: 'u1' } });
      fireEvent.change(screen.getByLabelText('Owning team'), { target: { value: 'Insight' } });
      fireEvent.change(screen.getByLabelText('Availability target (%)'), { target: { value: '99.5' } });
      fireEvent.click(screen.getByRole('button', { name: 'Save' }));

      await waitFor(() =>
        expect(createServiceMock).toHaveBeenCalledWith({
          name: 'reporting-api',
          description: undefined,
          ownerId: 'u1',
          team: 'Insight',
          escalationContact: undefined,
          // A number, matching the BigDecimal the API exchanges, not the text in the box.
          availabilityTarget: 99.5,
        }),
      );
    });

    it('prefills a numeric availability target without treating it as a string', async () => {
      // Regression: availabilityTarget is a JSON number; the edit form used to call .trim() on it and threw on open.
      listServicesMock.mockResolvedValue(page([SERVICE]));
      getServiceMock.mockResolvedValue(SERVICE);
      updateServiceMock.mockResolvedValue(SERVICE);

      renderPage('/services?open=svc-1', 'ADMIN');
      await screen.findByText('Handles checkout');
      fireEvent.click(screen.getByRole('button', { name: 'Edit service' }));

      expect(await screen.findByLabelText('Availability target (%)')).toHaveValue('99.9');

      fireEvent.click(screen.getByRole('button', { name: 'Save' }));
      await waitFor(() =>
        expect(updateServiceMock).toHaveBeenCalledWith('svc-1', expect.objectContaining({ availabilityTarget: 99.9 })),
      );
    });

    it('rejects a non-numeric availability target instead of sending NaN', async () => {
      listServicesMock.mockResolvedValue(page([SERVICE]));

      renderPage('/services', 'ADMIN');
      await screen.findByText('Checkout API', { selector: 'span' });
      fireEvent.click(screen.getByRole('button', { name: '+ Add service' }));
      fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'x' } });
      fireEvent.change(await screen.findByLabelText('Owner'), { target: { value: 'u1' } });
      fireEvent.change(screen.getByLabelText('Availability target (%)'), { target: { value: 'ninety' } });
      fireEvent.click(screen.getByRole('button', { name: 'Save' }));

      expect(await screen.findByText('Availability target must be a number.')).toBeInTheDocument();
      expect(createServiceMock).not.toHaveBeenCalled();
    });

    it('prefills the edit form from the selected service and PATCHes it', async () => {
      listServicesMock.mockResolvedValue(page([SERVICE]));
      getServiceMock.mockResolvedValue(SERVICE);
      updateServiceMock.mockResolvedValue({ ...SERVICE, name: 'checkout-api' });

      renderPage('/services?open=svc-1', 'ADMIN');
      await screen.findByText('Handles checkout');
      fireEvent.click(screen.getByRole('button', { name: 'Edit service' }));

      // Prefilled from the real service, not blank.
      expect((await screen.findByLabelText('Name')) as HTMLInputElement).toHaveValue('Checkout API');
      fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'checkout-api' } });
      fireEvent.click(screen.getByRole('button', { name: 'Save' }));

      await waitFor(() =>
        expect(updateServiceMock).toHaveBeenCalledWith('svc-1', expect.objectContaining({ name: 'checkout-api' })),
      );
    });
  });
});
