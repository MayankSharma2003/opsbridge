import { observer } from 'mobx-react-lite';
import { useTranslation } from 'react-i18next';
import { useAuthStore, useUiStore } from '../hooks/useStores';
import { PageHeader } from '../components/PageHeader';
import styles from './SettingsPage.module.css';

export const SettingsPage = observer(function SettingsPage() {
  const authStore = useAuthStore();
  const uiStore = useUiStore();
  const { t } = useTranslation();
  const user = authStore.user;

  return (
    <>
      <PageHeader title={t('pages.settings.title')} description={t('pages.settings.subtitle')} />

      <div className={styles.stack}>
        <section className={styles.card}>
          <div className={styles.cardTitle}>{t('pages.settings.profileTitle')}</div>
          {user && (
            <div className={styles.profileRow}>
              <span className={styles.avatar} aria-hidden="true">
                {user.name.slice(0, 2).toUpperCase()}
              </span>
              <div>
                <div className={styles.profileName}>{user.name}</div>
                <div className={styles.profileEmail}>{user.email}</div>
              </div>
              <span className={styles.roleBadge}>{t(`role.${user.role}`)}</span>
            </div>
          )}
        </section>

        <section className={styles.card}>
          <div className={styles.cardTitle}>{t('pages.settings.preferencesTitle')}</div>

          <div className={styles.prefRow}>
            <div>
              <div className={styles.prefLabel}>{t('pages.settings.language')}</div>
              <div className={styles.prefNote}>{t('pages.settings.languageNote')}</div>
            </div>
            {/* These are the language names themselves, not UI copy -- kept literal so a user can always recognize their own language. */}
            <div className={styles.pillGroup}>
              <button
                type="button"
                className={pillClass(styles, uiStore.locale === 'en')}
                onClick={() => uiStore.setLocale('en')}
              >
                English
              </button>
              <button
                type="button"
                className={pillClass(styles, uiStore.locale === 'ja')}
                onClick={() => uiStore.setLocale('ja')}
              >
                日本語
              </button>
            </div>
          </div>

          <div className={styles.prefRow}>
            <div>
              <div className={styles.prefLabel}>{t('pages.settings.theme')}</div>
              <div className={styles.prefNote}>{t('pages.settings.themeNote')}</div>
            </div>
            <div className={styles.pillGroup}>
              <button
                type="button"
                className={pillClass(styles, uiStore.theme === 'light')}
                onClick={() => uiStore.setTheme('light')}
              >
                {t('pages.settings.light')}
              </button>
              <button
                type="button"
                className={pillClass(styles, uiStore.theme === 'dark')}
                onClick={() => uiStore.setTheme('dark')}
              >
                {t('pages.settings.dark')}
              </button>
            </div>
          </div>
        </section>
      </div>
    </>
  );
});

function pillClass(styles: Record<string, string>, active: boolean): string {
  return active ? `${styles.pill} ${styles.pillActive}` : styles.pill;
}
