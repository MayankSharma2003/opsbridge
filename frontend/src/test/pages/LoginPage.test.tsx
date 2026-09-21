import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import '../../i18n';
import { LoginPage } from '../../pages/LoginPage';
import { StoreContext } from '../../stores/StoreContext';
import { RootStore } from '../../stores/RootStore';

function renderLogin(rootStore: RootStore, initialEntries: string[] = ['/login']) {
  render(
    <StoreContext.Provider value={rootStore}>
      <MemoryRouter initialEntries={initialEntries}>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/" element={<div>Dashboard landing</div>} />
          <Route path="/incidents" element={<div>Incidents landing</div>} />
        </Routes>
      </MemoryRouter>
    </StoreContext.Provider>,
  );
}

describe('LoginPage', () => {
  let rootStore: RootStore;

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    rootStore = new RootStore();
  });

  it('submits the entered email, password, and remember-me flag to AuthStore.login', async () => {
    const loginSpy = vi.spyOn(rootStore.authStore, 'login').mockResolvedValue();
    renderLogin(rootStore);

    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'operator@opsbridge.local' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'Operator#Dev2026' } });
    fireEvent.click(screen.getByLabelText('Keep me signed in on this workstation'));
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    await waitFor(() => {
      expect(loginSpy).toHaveBeenCalledWith('operator@opsbridge.local', 'Operator#Dev2026', false);
    });
  });

  it('shows the mapped error message when login fails', async () => {
    vi.spyOn(rootStore.authStore, 'login').mockImplementation(async () => {
      rootStore.authStore.loginError = 'auth.login.invalidCredentials';
      throw new Error('rejected');
    });
    renderLogin(rootStore);

    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'x@opsbridge.local' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'wrong' } });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Incorrect email or password.');
  });

  it('toggles the password field between hidden and revealed', () => {
    renderLogin(rootStore);

    const password = screen.getByLabelText('Password') as HTMLInputElement;
    expect(password.type).toBe('password');

    fireEvent.click(screen.getByRole('button', { name: 'Show' }));
    expect(password.type).toBe('text');

    fireEvent.click(screen.getByRole('button', { name: 'Hide' }));
    expect(password.type).toBe('password');
  });

  it('navigates to the originally-requested page after a successful login', async () => {
    vi.spyOn(rootStore.authStore, 'login').mockImplementation(async () => {
      rootStore.authStore.user = { id: 'u1', name: 'Dev Operator', email: 'operator@opsbridge.local', role: 'OPERATOR' };
    });
    render(
      <StoreContext.Provider value={rootStore}>
        <MemoryRouter initialEntries={[{ pathname: '/login', state: { from: { pathname: '/incidents' } } }]}>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/incidents" element={<div>Incidents landing</div>} />
          </Routes>
        </MemoryRouter>
      </StoreContext.Provider>,
    );

    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'operator@opsbridge.local' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'Operator#Dev2026' } });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByText('Incidents landing')).toBeInTheDocument();
  });

  it('redirects away from /login when already authenticated', () => {
    rootStore.authStore.user = { id: 'u1', name: 'Dev Admin', email: 'admin@opsbridge.local', role: 'ADMIN' };

    renderLogin(rootStore);

    expect(screen.getByText('Dashboard landing')).toBeInTheDocument();
  });
});
