import i18next from 'i18next';
import { initReactI18next } from 'react-i18next';
import en from './en.json';
import ja from './ja.json';

// No browser-language auto-detection -- English is the spec'd default. UiStore overrides this once
// it constructs and restores a returning user's saved locale; this is only what shows before that.
void i18next.use(initReactI18next).init({
  resources: { en: { translation: en }, ja: { translation: ja } },
  lng: 'en',
  fallbackLng: 'en',
  interpolation: { escapeValue: false },
});

export default i18next;
