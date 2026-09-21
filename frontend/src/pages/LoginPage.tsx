import { useId, useState, type FormEvent } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { observer } from 'mobx-react-lite';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '../hooks/useStores';
import styles from './LoginPage.module.css';

interface LocationState {
  from?: { pathname: string };
}

export const LoginPage = observer(function LoginPage() {
  const authStore = useAuthStore();
  const navigate = useNavigate();
  const location = useLocation();
  const { t } = useTranslation();

  const emailId = useId();
  const passwordId = useId();
  const rememberId = useId();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [remember, setRemember] = useState(true);
  const [reveal, setReveal] = useState(false);

  if (authStore.isAuthenticated) {
    const from = (location.state as LocationState | null)?.from?.pathname ?? '/';
    return <Navigate to={from} replace />;
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    try {
      await authStore.login(email, password, remember);
      const from = (location.state as LocationState | null)?.from?.pathname ?? '/';
      navigate(from, { replace: true });
    } catch {
      // loginError already holds the i18n key to show; nothing else to do here.
    }
  }

  return (
    <div className={styles.page}>
      <div className={styles.glow} aria-hidden="true">
        <div className={styles.glowAccent} />
        <div className={styles.glowInfo} />
      </div>

      <div className={styles.content}>
        <div className={styles.brand}>
          <span className={styles.brandMark} aria-hidden="true">
            ⏳
          </span>
          <div>
            <div className={styles.brandName}>{t('app.name')}</div>
            <div className={styles.brandTagline}>{t('app.tagline')}</div>
          </div>
        </div>

        <form className={styles.card} onSubmit={handleSubmit} noValidate>
          <h1 className={styles.cardTitle}>{t('auth.login.title')}</h1>
          <p className={styles.cardSubtitle}>{t('auth.login.subtitle')}</p>

          {authStore.loginError && (
            <div className={styles.error} role="alert">
              {t(authStore.loginError)}
            </div>
          )}

          <label className={styles.field} htmlFor={emailId}>
            {t('auth.login.emailLabel')}
          </label>
          <input
            id={emailId}
            type="email"
            required
            autoComplete="username"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            placeholder={t('auth.login.emailPlaceholder')}
            className={styles.input}
          />

          <div className={styles.fieldRow}>
            <label className={styles.field} htmlFor={passwordId} style={{ marginBottom: 0 }}>
              {t('auth.login.passwordLabel')}
            </label>
            <button type="button" className={styles.revealToggle} onClick={() => setReveal((value) => !value)}>
              {reveal ? t('auth.login.passwordHide') : t('auth.login.passwordShow')}
            </button>
          </div>
          <input
            id={passwordId}
            type={reveal ? 'text' : 'password'}
            required
            autoComplete="current-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            className={styles.input}
          />

          <button type="submit" className={styles.submit} disabled={authStore.loginPending}>
            {authStore.loginPending ? t('auth.login.submitPending') : t('auth.login.submit')}
          </button>

          <label className={styles.remember} htmlFor={rememberId}>
            <input
              id={rememberId}
              type="checkbox"
              checked={remember}
              onChange={(event) => setRemember(event.target.checked)}
            />
            {t('auth.login.rememberMe')}
          </label>
        </form>

        <div className={styles.disclaimer}>
          <span className={styles.disclaimerDot} aria-hidden="true" />
          {t('auth.login.disclaimer')}
        </div>
      </div>
    </div>
  );
});
