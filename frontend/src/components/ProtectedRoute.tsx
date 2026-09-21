import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { observer } from 'mobx-react-lite';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '../hooks/useStores';
import { useLiveConnection } from '../hooks/useLiveConnection';

// Redirects to /login when unauthenticated, preserving the requested path for LoginPage to return
// to. Waits out session restoration first so a refresh doesn't flash the login screen. Also owns
// the live channel -- it's the one boundary every authenticated screen sits behind, so the
// connection survives list -> detail navigation instead of being unmounted with AppShell.
export const ProtectedRoute = observer(function ProtectedRoute() {
  const authStore = useAuthStore();
  const location = useLocation();
  const { t } = useTranslation();
  useLiveConnection();

  if (authStore.sessionStatus === 'restoring') {
    return (
      <div role="status" style={{ display: 'grid', placeItems: 'center', minHeight: '100vh' }}>
        {t('common.loading')}
      </div>
    );
  }

  if (!authStore.isAuthenticated) {
    return <Navigate to="/login" replace state={{ from: location }} />;
  }

  return <Outlet />;
});
