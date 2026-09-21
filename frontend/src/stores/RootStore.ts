import { AuthStore } from './AuthStore';
import { IncidentStore } from './IncidentStore';
import { ServiceStore } from './ServiceStore';
import { DashboardStore } from './DashboardStore';
import { UiStore } from './UiStore';
import { LiveStore } from './LiveStore';

// composes every domain store (spec 7.2), each owning its own domain and nothing else
export class RootStore {
  readonly uiStore: UiStore;
  readonly authStore: AuthStore;
  readonly incidentStore: IncidentStore;
  readonly serviceStore: ServiceStore;
  readonly dashboardStore: DashboardStore;
  readonly liveStore: LiveStore;

  constructor() {
    this.uiStore = new UiStore();
    this.authStore = new AuthStore();
    this.incidentStore = new IncidentStore();
    this.serviceStore = new ServiceStore();
    this.dashboardStore = new DashboardStore();
    // last -- routes live events into the stores above, so they must exist first
    this.liveStore = new LiveStore(this.incidentStore, this.dashboardStore);
  }
}
