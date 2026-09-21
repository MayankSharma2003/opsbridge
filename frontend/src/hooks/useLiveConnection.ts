import { useEffect } from 'react';
import { useAuthStore, useLiveStore } from './useStores';

// Opens once auth completes and closes on logout, so a signed-out tab never keeps a connection open.
// Mounted by ProtectedRoute rather than AppShell because incident detail renders outside the shell.
export function useLiveConnection(): void {
  const authStore = useAuthStore();
  const liveStore = useLiveStore();
  const isAuthenticated = authStore.isAuthenticated;

  useEffect(() => {
    if (!isAuthenticated) return;
    liveStore.connect();
    return () => liveStore.disconnect();
  }, [isAuthenticated, liveStore]);
}
