import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import '../../i18n';
import { ProtectedRoute } from '../../components/ProtectedRoute';
import { StoreContext } from '../../stores/StoreContext';
import { RootStore } from '../../stores/RootStore';

function renderProtected(rootStore: RootStore) {
  return render(
    <StoreContext.Provider value={rootStore}>
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route path="/login" element={<div>Login page</div>} />
          <Route element={<ProtectedRoute />}>
            <Route path="/" element={<div>Protected content</div>} />
          </Route>
        </Routes>
      </MemoryRouter>
    </StoreContext.Provider>,
  );
}

describe('ProtectedRoute', () => {
  it('shows a loading state while the session is still restoring', () => {
    const rootStore = new RootStore();
    rootStore.authStore.sessionStatus = 'restoring';

    renderProtected(rootStore);

    expect(screen.getByRole('status')).toBeInTheDocument();
    expect(screen.queryByText('Protected content')).not.toBeInTheDocument();
    expect(screen.queryByText('Login page')).not.toBeInTheDocument();
  });

  it('redirects to /login once restoration finishes with no authenticated user', () => {
    const rootStore = new RootStore();
    rootStore.authStore.sessionStatus = 'ready';

    renderProtected(rootStore);

    expect(screen.getByText('Login page')).toBeInTheDocument();
  });

  it('renders the protected content once restoration finishes with an authenticated user', () => {
    const rootStore = new RootStore();
    rootStore.authStore.sessionStatus = 'ready';
    rootStore.authStore.user = { id: 'u1', name: 'Dev Admin', email: 'admin@opsbridge.local', role: 'ADMIN' };

    renderProtected(rootStore);

    expect(screen.getByText('Protected content')).toBeInTheDocument();
  });

  it('opens the live channel for authenticated screens, including ones outside AppShell', () => {
    const rootStore = new RootStore();
    rootStore.authStore.sessionStatus = 'ready';
    rootStore.authStore.user = { id: 'u1', name: 'Dev Admin', email: 'admin@opsbridge.local', role: 'ADMIN' };

    // This route has no AppShell, same as /incidents/:id.
    renderProtected(rootStore);

    expect(rootStore.liveStore.status).toBe('connecting');
  });

  it('opens nothing while the session is still being restored', () => {
    const rootStore = new RootStore();
    rootStore.authStore.sessionStatus = 'restoring';

    renderProtected(rootStore);

    expect(rootStore.liveStore.status).toBe('idle');
  });

  it('opens nothing for a visitor who is not signed in', () => {
    const rootStore = new RootStore();
    rootStore.authStore.sessionStatus = 'ready';

    renderProtected(rootStore);

    expect(rootStore.liveStore.status).toBe('idle');
  });
});
