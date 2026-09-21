import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { UiStore } from '../../stores/UiStore';
import i18next from '../../i18n';

const STORAGE_KEY = 'opsbridge.ui';

describe('UiStore', () => {
  beforeEach(() => {
    localStorage.clear();
    document.documentElement.removeAttribute('data-ob-theme');
  });

  afterEach(() => {
    void i18next.changeLanguage('en');
  });

  it('defaults to light and English when nothing is persisted and the OS has no dark preference', () => {
    const store = new UiStore();

    expect(store.theme).toBe('light');
    expect(store.locale).toBe('en');
    expect(document.documentElement.getAttribute('data-ob-theme')).toBe('light');
  });

  it('restores a previously persisted theme and locale', () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ theme: 'dark', locale: 'ja' }));

    const store = new UiStore();

    expect(store.theme).toBe('dark');
    expect(store.locale).toBe('ja');
    expect(document.documentElement.getAttribute('data-ob-theme')).toBe('dark');
    expect(i18next.language).toBe('ja');
  });

  it('falls back to defaults when the persisted value is corrupt JSON', () => {
    localStorage.setItem(STORAGE_KEY, '{not json');

    const store = new UiStore();

    expect(store.theme).toBe('light');
    expect(store.locale).toBe('en');
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it('setTheme updates document.documentElement and persists the choice', () => {
    const store = new UiStore();

    store.setTheme('dark');

    expect(store.theme).toBe('dark');
    expect(document.documentElement.getAttribute('data-ob-theme')).toBe('dark');
    expect(JSON.parse(localStorage.getItem(STORAGE_KEY)!)).toEqual({ theme: 'dark', locale: 'en' });
  });

  it('setLocale switches the active i18next language and persists the choice', () => {
    const store = new UiStore();

    store.setLocale('ja');

    expect(store.locale).toBe('ja');
    expect(i18next.language).toBe('ja');
    expect(JSON.parse(localStorage.getItem(STORAGE_KEY)!)).toEqual({ theme: 'light', locale: 'ja' });
  });
});
