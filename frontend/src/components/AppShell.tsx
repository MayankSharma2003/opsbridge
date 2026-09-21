import { NavLink, Outlet } from 'react-router-dom';
import { observer } from 'mobx-react-lite';
import { useTranslation } from 'react-i18next';
import { useAuthStore, useLiveStore } from '../hooks/useStores';
import type { Role } from '../types/auth';
import styles from './AppShell.module.css';

interface NavItem {
  to: string;
  labelKey: string;
  /** Omitted means every authenticated role sees it. */
  roles?: Role[];
}

// On-call and Postmortems are in the design prototypes but have no route/page/backend here --
// omitted rather than shipped as dead links.
export const NAV_ITEMS: NavItem[] = [
  { to: '/', labelKey: 'nav.dashboard' },
  { to: '/incidents', labelKey: 'nav.incidents' },
  { to: '/services', labelKey: 'nav.services' },
  { to: '/settings', labelKey: 'nav.settings' },
];

// Nothing is filtered out today (every destination is readable by all three roles) -- this exists
// so a future role-restricted destination is just a `roles` list on its NavItem.
export function filterNavItemsByRole(items: NavItem[], role: Role | null): NavItem[] {
  return items.filter((item) => !item.roles || (role !== null && item.roles.includes(role)));
}

// sidebar nav + sign-out and the route outlet; each page owns its own topbar/title (see PageHeader)
export const AppShell = observer(function AppShell() {
  const authStore = useAuthStore();
  const liveStore = useLiveStore();
  const { t } = useTranslation();
  const visibleItems = filterNavItemsByRole(NAV_ITEMS, authStore.role);

  return (
    <div className={styles.shell}>
      <aside className={styles.sidebar}>
        <div className={styles.brand}>
          <span className={styles.brandMark} aria-hidden="true">
            ⏳
          </span>
          <span className={styles.brandName}>{t('app.name')}</span>
        </div>

        <nav className={styles.nav} aria-label={t('app.name')}>
          {visibleItems.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.to === '/'}
              className={({ isActive }) => (isActive ? `${styles.navItem} ${styles.navItemActive}` : styles.navItem)}
            >
              {t(item.labelKey)}
            </NavLink>
          ))}
        </nav>

        <div className={styles.sidebarFooter}>
          {/* push-channel status, not the app's -- when down, screens still work but stop auto-updating */}
          <div className={styles.liveStatus} role="status">
            <span
              className={`${styles.liveDot} ${liveStore.isLive ? styles.liveDotOn : styles.liveDotOff}`}
              aria-hidden="true"
            />
            {t(`live.${liveStore.status}`)}
          </div>
          {authStore.user && (
            <>
              <div className={styles.userLine}>{t('nav.signedInAs', { name: authStore.user.name })}</div>
              <div className={styles.roleBadge}>{t(`role.${authStore.user.role}`)}</div>
            </>
          )}
          <button type="button" className={styles.signOut} onClick={() => authStore.logout()}>
            {t('nav.signOut')}
          </button>
        </div>
      </aside>

      <div className={styles.main}>
        {/* padding here is load-bearing -- PageHeader/filter bars use negative margins to bleed to the edge */}
        <div className={styles.content}>
          <Outlet />
        </div>
      </div>
    </div>
  );
});
