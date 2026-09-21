import type { ServiceSummary } from './service';
import type { UserSummary } from './user';

export type IncidentStatus = 'OPEN' | 'TRIAGED' | 'INVESTIGATING' | 'MITIGATED' | 'RESOLVED' | 'CANCELLED';
export type Priority = 'P1' | 'P2' | 'P3' | 'P4';
export type Severity = 'SEV1' | 'SEV2' | 'SEV3' | 'SEV4';
export type SlaState = 'ON_TRACK' | 'AT_RISK' | 'BREACHED' | 'MET';

export type ActivityEventType =
  | 'CREATED'
  | 'ASSIGNED'
  | 'REASSIGNED'
  | 'TRIAGED'
  | 'STATUS_CHANGED'
  | 'ESCALATED'
  | 'PRIORITY_CHANGED'
  | 'SEVERITY_CHANGED'
  | 'SERVICE_LINKED'
  | 'RESOLUTION_NOTE_ADDED'
  | 'SLA_BREACHED'
  | 'TITLE_CHANGED'
  | 'DESCRIPTION_CHANGED'
  | 'SERVICE_CREATED'
  | 'SERVICE_UPDATED'
  | 'SERVICE_ARCHIVED';

export interface IncidentSummary {
  id: string;
  publicKey: string;
  title: string;
  service: ServiceSummary;
  assignee: UserSummary | null;
  priority: Priority;
  severity: Severity;
  status: IncidentStatus;
  slaState: SlaState;
  slaResolutionTargetAt: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface IncidentSla {
  state: SlaState;
  startedAt: string | null;
  responseTargetAt: string | null;
  mitigationTargetAt: string | null;
  resolutionTargetAt: string | null;
  respondedAt: string | null;
  mitigatedAt: string | null;
  resolvedAt: string | null;
  breachedAt: string | null;
}

export interface IncidentDetail {
  id: string;
  publicKey: string;
  title: string;
  description: string | null;
  service: ServiceSummary;
  reporter: UserSummary;
  assignee: UserSummary | null;
  priority: Priority;
  severity: Severity;
  status: IncidentStatus;
  sla: IncidentSla;
  resolutionNotes: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface IncidentActivityEntry {
  id: string;
  actor: UserSummary;
  eventType: ActivityEventType;
  fieldName: string | null;
  fromValue: string | null;
  toValue: string | null;
  comment: string | null;
  createdAt: string;
}

export interface IncidentSearchParams {
  q?: string;
  status?: IncidentStatus;
  priority?: Priority;
  severity?: Severity;
  serviceId?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
  sort?: string;
}

// Reporter comes from the authenticated caller server-side, never sent here.
export interface CreateIncidentRequest {
  title: string;
  description?: string;
  serviceId: string;
  priority: Priority;
  severity: Severity;
}

export interface TransitionIncidentRequest {
  expectedVersion: number;
  targetStatus: IncidentStatus;
  comment?: string;
  resolutionNotes?: string;
}

export interface AssignIncidentRequest {
  expectedVersion: number;
  assigneeId: string | null;
}
