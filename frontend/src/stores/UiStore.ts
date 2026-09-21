import { makeAutoObservable } from 'mobx';
import i18next from '../i18n';

export type Theme = 'light' | 'dark';
export type Locale = 'en' | 'ja';

const STORAGE_KEY = 'opsbridge.ui';
const THEME_ATTRIBUTE = 'data-ob-theme';

interface PersistedUi {
  theme: Theme;
  locale: Locale;
}

// Theme and locale (spec 7.2), persisted across sessions (spec 7.3); owns nothing else.
// This is the only place allowed to set data-ob-theme on document.documentElement -- tokens.css
// only checks :root's attribute, so no page or component should set its own.
export class UiStore {
  theme: Theme;
  locale: Locale;

  constructor() {
    const persisted = readPersisted();
    this.theme = persisted?.theme ?? systemPreferredTheme();
    this.locale = persisted?.locale ?? 'en';
    makeAutoObservable(this);
    this.applyTheme();
    void i18next.changeLanguage(this.locale);
  }

  setTheme(theme: Theme): void {
    this.theme = theme;
    this.applyTheme();
    this.persist();
  }

  setLocale(locale: Locale): void {
    this.locale = locale;
    void i18next.changeLanguage(locale);
    this.persist();
  }

  private applyTheme(): void {
    document.documentElement.setAttribute(THEME_ATTRIBUTE, this.theme);
  }

  private persist(): void {
    const value: PersistedUi = { theme: this.theme, locale: this.locale };
    localStorage.setItem(STORAGE_KEY, JSON.stringify(value));
  }
}

function systemPreferredTheme(): Theme {
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

function readPersisted(): PersistedUi | null {
  const raw = localStorage.getItem(STORAGE_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as PersistedUi;
  } catch {
    localStorage.removeItem(STORAGE_KEY);
    return null;
  }
}
