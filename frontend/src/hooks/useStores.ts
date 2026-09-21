import { useContext } from 'react';
import { StoreContext } from '../stores/StoreContext';
import type { RootStore } from '../stores/RootStore';
import type { AuthStore } from '../stores/AuthStore';
import type { IncidentStore } from '../stores/IncidentStore';
import type { ServiceStore } from '../stores/ServiceStore';
import type { DashboardStore } from '../stores/DashboardStore';
import type { UiStore } from '../stores/UiStore';
import type { LiveStore } from '../stores/LiveStore';

export function useRootStore(): RootStore {
  const store = useContext(StoreContext);
  if (!store) {
    throw new Error('useRootStore must be used within a StoreProvider');
  }
  return store;
}

export function useAuthStore(): AuthStore {
  return useRootStore().authStore;
}

export function useIncidentStore(): IncidentStore {
  return useRootStore().incidentStore;
}

export function useServiceStore(): ServiceStore {
  return useRootStore().serviceStore;
}

export function useDashboardStore(): DashboardStore {
  return useRootStore().dashboardStore;
}

export function useUiStore(): UiStore {
  return useRootStore().uiStore;
}

export function useLiveStore(): LiveStore {
  return useRootStore().liveStore;
}
