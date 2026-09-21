import type { UserSummary } from './user';

export type ServiceStatus = 'ACTIVE' | 'ARCHIVED';

export interface ServiceSummary {
  id: string;
  name: string;
}

export interface ServiceDetail {
  id: string;
  name: string;
  description: string | null;
  owner: UserSummary;
  team: string | null;
  escalationContact: string | null;
  /** JSON number: the backend's BigDecimal serialises as a number, not a string. */
  availabilityTarget: number | null;
  status: ServiceStatus;
  /** Live counts of non-terminal incidents against this service, computed server-side. */
  openIncidentCount: number;
  /** P1/P2 subset of openIncidentCount -- distinguishes a critical service from a merely watched one. */
  urgentOpenIncidentCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface CreateServiceRequest {
  name: string;
  description?: string;
  ownerId: string;
  team?: string;
  escalationContact?: string;
  availabilityTarget?: number;
}

/** Every field optional: the backend leaves nulls unchanged (UpdateServiceRequest). */
export type UpdateServiceRequest = Partial<CreateServiceRequest>;
