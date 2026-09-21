import { describe, expect, it, vi, beforeEach } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import '../../i18n';
import { AppShell, filterNavItemsByRole } from '../../components/AppShell';
import { StoreContext } from '../../stores/StoreContext';
import { RootStore } from '../../stores/RootStore';
import type { Role } from '../../types/auth';

function renderShell(role: Role) {
  const rootStore = new RootStore();
  rootStore.authStore.user = { id: 'u1', name: 'Dev Operator', email: 'operator@opsbridge.local', role };
  render(
    <StoreContext.Provider value={rootStore}>
      <MemoryRouter>
        <AppShell />
      </MemoryRouter>
    </StoreContext.Provider>,
  );
  return rootStore;
}

describe('AppShell', () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  it('renders every current nav destination -- none of them are role-restricted today', () => {
    renderShell('VIEWER');

    expect(screen.getByRole('link', { name: 'Dashboard' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Incidents' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Services' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Settings' })).toBeInTheDocument();
  });

  it('shows who is signed in and their role', () => {
    renderShell('ADMIN');

    expect(screen.getByText('Signed in as Dev Operator')).toBeInTheDocument();
    expect(screen.getByText('Admin')).toBeInTheDocument();
  });

  it('reports the channel status without owning the connection', () => {
    // Ownership sits in ProtectedRoute since incident detail renders outside this shell -- rendered alone, the shell just shows the channel as found.
    const rootStore = renderShell('OPERATOR');

    expect(rootStore.liveStore.status).toBe('idle');
    expect(screen.getByText('Live updates off')).toBeInTheDocument();
  });

  it('shows the live channel status once it is connected', () => {
    const rootStore = renderShell('OPERATOR');

    act(() => {
      rootStore.liveStore.handleReady();
    });

    expect(screen.getByText('Live')).toBeInTheDocument();
  });

  it('tells the user when the channel is retrying, without asking them to do anything', () => {
    const rootStore = renderShell('OPERATOR');

    act(() => {
      rootStore.liveStore.handleReady();
      rootStore.liveStore.handleReconnecting(1);
    });

    expect(screen.getByText('Reconnecting…')).toBeInTheDocument();
  });

  it('calls authStore.logout() when Sign out is clicked', () => {
    const rootStore = renderShell('OPERATOR');
    const logoutSpy = vi.spyOn(rootStore.authStore, 'logout');

    fireEvent.click(screen.getByRole('button', { name: 'Sign out' }));

    expect(logoutSpy).toHaveBeenCalledOnce();
  });
});

describe('filterNavItemsByRole', () => {
  const items = [
    { to: '/', labelKey: 'nav.dashboard' },
    { to: '/admin-only', labelKey: 'nav.settings', roles: ['ADMIN' as const] },
  ];

  it('keeps items with no roles restriction for any role', () => {
    expect(filterNavItemsByRole(items, 'VIEWER').map((i) => i.to)).toContain('/');
  });

  it('hides a role-restricted item from a role not on its list', () => {
    expect(filterNavItemsByRole(items, 'VIEWER').map((i) => i.to)).not.toContain('/admin-only');
  });

  it('shows a role-restricted item to a role on its list', () => {
    expect(filterNavItemsByRole(items, 'ADMIN').map((i) => i.to)).toContain('/admin-only');
  });

  it('hides every restricted item when there is no authenticated role', () => {
    expect(filterNavItemsByRole(items, null).map((i) => i.to)).not.toContain('/admin-only');
  });
});
