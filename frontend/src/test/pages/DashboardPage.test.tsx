import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import '../../i18n';

vi.mock('../../api/dashboardApi', () => ({
  getDashboardSummary: vi.fn(),
}));
vi.mock('../../api/incidentApi', () => ({
  listIncidents: vi.fn(),
}));

import { getDashboardSummary } from '../../api/dashboardApi';
import { listIncidents } from '../../api/incidentApi';
import { DashboardPage } from '../../pages/DashboardPage';
import { StoreContext } from '../../stores/StoreContext';
import { RootStore } from '../../stores/RootStore';
import type { DashboardSummary } from '../../types/dashboard';
import dashboardStyles from '../../pages/DashboardPage.module.css';

const getDashboardSummaryMock = vi.mocked(getDashboardSummary);
const listIncidentsMock = vi.mocked(listIncidents);

const RECENT = {
  id: 'inc-1',
  publicKey: 'INC-1000',
  title: 'Checkout is down',
  service: { id: 'svc-1', name: 'Checkout API' },
  assignee: null,
  priority: 'P1' as const,
  severity: 'SEV1' as const,
  status: 'OPEN' as const,
  slaState: 'ON_TRACK' as const,
  slaResolutionTargetAt: null,
  version: 0,
  createdAt: '2026-03-01T00:00:00Z',
  updatedAt: '2026-03-01T00:00:00Z',
};

const recentPage = (content: (typeof RECENT)[]) => ({
  content,
  page: 0,
  size: 7,
  totalElements: content.length,
  totalPages: 1,
  first: true,
  last: true,
});

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
    { date: '2026-02-24', mttrMinutes: 50 },
  ],
};

const EMPTY_SUMMARY: DashboardSummary = {
  kpis: { openIncidents: 0, unassignedIncidents: 0, slaBreachesThisWeek: 0, mttrMinutes: null },
  priorityDistribution: [
    { priority: 'P1', count: 0 },
    { priority: 'P2', count: 0 },
    { priority: 'P3', count: 0 },
    { priority: 'P4', count: 0 },
  ],
  mttrTrend: [],
};

function renderPage() {
  const rootStore = new RootStore();
  render(
    <StoreContext.Provider value={rootStore}>
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route path="/" element={<DashboardPage />} />
          <Route path="/incidents" element={<div>List page</div>} />
          <Route path="/incidents/:id" element={<div>Detail page</div>} />
        </Routes>
      </MemoryRouter>
    </StoreContext.Provider>,
  );
  return rootStore;
}

describe('DashboardPage', () => {
  beforeEach(() => {
    getDashboardSummaryMock.mockReset();
    listIncidentsMock.mockReset();
    listIncidentsMock.mockResolvedValue(recentPage([]));
  });

  it('shows the error state and lets the user retry', async () => {
    getDashboardSummaryMock.mockRejectedValueOnce(new Error('boom'));
    getDashboardSummaryMock.mockResolvedValueOnce(SUMMARY);

    renderPage();

    expect(await screen.findByText("Couldn't load the dashboard")).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));

    expect(await screen.findByText('Open incidents')).toBeInTheDocument();
  });

  it('shows the empty state when there is no operational data yet', async () => {
    getDashboardSummaryMock.mockResolvedValue(EMPTY_SUMMARY);

    renderPage();

    expect(await screen.findByText('No operational data yet')).toBeInTheDocument();
  });

  it('renders real KPI values, priority bars, and mttr trend weekday labels from the summary', async () => {
    getDashboardSummaryMock.mockResolvedValue(SUMMARY);

    renderPage();

    expect(await screen.findByText('4')).toBeInTheDocument();
    expect(screen.getByText('41m')).toBeInTheDocument();
    expect(screen.getByText('P1')).toBeInTheDocument();
    expect(screen.getByText('critical')).toBeInTheDocument();
    expect(screen.getByText('Mon')).toBeInTheDocument();
    expect(screen.getByText('Tue')).toBeInTheDocument();
  });

  it('shows "no resolutions yet" instead of a fabricated mttr value when mttrMinutes is null', async () => {
    getDashboardSummaryMock.mockResolvedValue({
      ...SUMMARY,
      kpis: { ...SUMMARY.kpis, mttrMinutes: null },
    });

    renderPage();

    expect(await screen.findByText('No resolutions yet')).toBeInTheDocument();
  });

  it('shows each priority as a share of all open incidents, not just a raw count', async () => {
    getDashboardSummaryMock.mockResolvedValue(SUMMARY);

    renderPage();
    await screen.findByText('4');

    // P1 1, P2 2, P3 1, P4 0 of 4 open -- P1 and P3 share the same 25% line.
    expect(screen.getAllByText('open · 1 · 25%')).toHaveLength(2);
    expect(screen.getByText('open · 2 · 50%')).toBeInTheDocument();
    expect(screen.getByText('open · 0 · 0%')).toBeInTheDocument();
  });

  it('falls back to a bare count rather than dividing by zero when nothing is open', async () => {
    getDashboardSummaryMock.mockResolvedValue({
      kpis: { openIncidents: 0, unassignedIncidents: 0, slaBreachesThisWeek: 1, mttrMinutes: 20 },
      priorityDistribution: [
        { priority: 'P1', count: 0 },
        { priority: 'P2', count: 0 },
        { priority: 'P3', count: 0 },
        { priority: 'P4', count: 0 },
      ],
      mttrTrend: [],
    });

    renderPage();
    await screen.findByText('Open incidents');

    expect(screen.getAllByText('open · 0')).toHaveLength(4);
    expect(screen.queryByText(/NaN/)).not.toBeInTheDocument();
  });

  it('shows a priority breakdown strip under the open-incidents count, from the same real distribution shown below', async () => {
    getDashboardSummaryMock.mockResolvedValue(SUMMARY);

    renderPage();
    await screen.findByText('4');

    // P4's count is 0, so only three non-zero priorities get a segment.
    const segments = document.querySelectorAll(`.${dashboardStyles.kpiBreakdownSegment}`);
    expect(segments).toHaveLength(3);
    expect(segments[0].className).toContain(dashboardStyles['bar-P1']);
    expect((segments[0] as HTMLElement).style.width).toBe('25%'); // 1 of 4 open
    expect(segments[1].className).toContain(dashboardStyles['bar-P2']);
    expect((segments[1] as HTMLElement).style.width).toBe('50%'); // 2 of 4 open
  });

  it('shows no breakdown strip when there are no open incidents', async () => {
    getDashboardSummaryMock.mockResolvedValue({
      kpis: { openIncidents: 0, unassignedIncidents: 0, slaBreachesThisWeek: 1, mttrMinutes: 20 },
      priorityDistribution: [
        { priority: 'P1', count: 0 },
        { priority: 'P2', count: 0 },
        { priority: 'P3', count: 0 },
        { priority: 'P4', count: 0 },
      ],
      mttrTrend: [],
    });

    renderPage();
    await screen.findByText('Open incidents');

    expect(document.querySelectorAll(`.${dashboardStyles.kpiBreakdownSegment}`)).toHaveLength(0);
  });

  describe('unassigned count', () => {
    it('shows how much open work has nobody on it', async () => {
      getDashboardSummaryMock.mockResolvedValue({
        ...SUMMARY,
        kpis: { ...SUMMARY.kpis, openIncidents: 4, unassignedIncidents: 3 },
      });

      renderPage();

      expect(await screen.findByText('3 unassigned')).toBeInTheDocument();
    });

    it('still says zero unassigned when everything is owned', async () => {
      getDashboardSummaryMock.mockResolvedValue({
        ...SUMMARY,
        kpis: { ...SUMMARY.kpis, openIncidents: 4, unassignedIncidents: 0 },
      });

      renderPage();

      expect(await screen.findByText('0 unassigned')).toBeInTheDocument();
    });
  });

  describe('recent incidents', () => {
    it('lists the newest incidents and opens one on click', async () => {
      getDashboardSummaryMock.mockResolvedValue(SUMMARY);
      listIncidentsMock.mockResolvedValue(recentPage([RECENT]));

      renderPage();

      expect(await screen.findByText('Recent incidents')).toBeInTheDocument();
      const row = await screen.findByText('Checkout is down');
      fireEvent.click(row.closest('tr')!);

      expect(await screen.findByText('Detail page')).toBeInTheDocument();
    });

    it('shows an empty row rather than a blank card when there are no incidents', async () => {
      getDashboardSummaryMock.mockResolvedValue(SUMMARY);
      listIncidentsMock.mockResolvedValue(recentPage([]));

      renderPage();

      expect(await screen.findByText('No incidents yet')).toBeInTheDocument();
    });

    it('keeps the KPIs usable when only the feed fails', async () => {
      getDashboardSummaryMock.mockResolvedValue(SUMMARY);
      listIncidentsMock.mockRejectedValue(new Error('boom'));

      renderPage();

      expect(await screen.findByText("Couldn't load recent incidents")).toBeInTheDocument();
      // Rest of the dashboard is unaffected by one card failing.
      expect(screen.getByText('Open incidents')).toBeInTheDocument();
      expect(screen.getByText('41m')).toBeInTheDocument();
    });

    it('links through to the full incident list', async () => {
      getDashboardSummaryMock.mockResolvedValue(SUMMARY);
      listIncidentsMock.mockResolvedValue(recentPage([RECENT]));

      renderPage();
      fireEvent.click(await screen.findByRole('button', { name: 'View all' }));

      expect(await screen.findByText('List page')).toBeInTheDocument();
    });
  });
});
