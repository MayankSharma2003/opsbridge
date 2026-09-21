import { describe, expect, it, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import '../../i18n';
import i18next from '../../i18n';
import { SettingsPage } from '../../pages/SettingsPage';
import { StoreContext } from '../../stores/StoreContext';
import { RootStore } from '../../stores/RootStore';

function renderPage() {
  const rootStore = new RootStore();
  rootStore.authStore.user = { id: 'u1', name: 'Dev Operator', email: 'operator@opsbridge.local', role: 'ADMIN' };
  render(
    <StoreContext.Provider value={rootStore}>
      <SettingsPage />
    </StoreContext.Provider>,
  );
  return rootStore;
}

describe('SettingsPage', () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    document.documentElement.removeAttribute('data-ob-theme');
    void i18next.changeLanguage('en');
  });

  it('shows the real signed-in profile, not fabricated fields', () => {
    renderPage();

    expect(screen.getByText('Dev Operator')).toBeInTheDocument();
    expect(screen.getByText('operator@opsbridge.local')).toBeInTheDocument();
    expect(screen.getByText('Admin')).toBeInTheDocument();
  });

  it('switching to 日本語 changes the active i18next language but keeps the picker labels literal', () => {
    renderPage();

    fireEvent.click(screen.getByText('日本語'));

    expect(i18next.language).toBe('ja');
    expect(screen.getByText('日本語')).toBeInTheDocument();
    expect(screen.getByText('English')).toBeInTheDocument();
  });

  it('switching to Dark applies the theme attribute to the document root', () => {
    const rootStore = renderPage();

    fireEvent.click(screen.getByText('Dark'));

    expect(rootStore.uiStore.theme).toBe('dark');
    expect(document.documentElement.getAttribute('data-ob-theme')).toBe('dark');
  });

  it('persists theme and locale choices across a store reconstruction', () => {
    renderPage();

    fireEvent.click(screen.getByText('Dark'));
    fireEvent.click(screen.getByText('日本語'));

    const rehydrated = new RootStore();
    expect(rehydrated.uiStore.theme).toBe('dark');
    expect(rehydrated.uiStore.locale).toBe('ja');
  });

  it('tells the user their preference changes take effect immediately', () => {
    renderPage();

    expect(screen.getByText('Changes apply immediately')).toBeInTheDocument();
  });
});
