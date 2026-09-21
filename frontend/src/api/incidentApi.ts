import { apiRequest, buildQuery } from './client';
import type { PageResponse } from '../types/pagination';
import type {
  AssignIncidentRequest,
  CreateIncidentRequest,
  IncidentActivityEntry,
  IncidentDetail,
  IncidentSearchParams,
  IncidentSummary,
  TransitionIncidentRequest,
} from '../types/incident';

export function listIncidents(params: IncidentSearchParams): Promise<PageResponse<IncidentSummary>> {
  const query = buildQuery({
    q: params.q,
    status: params.status,
    priority: params.priority,
    severity: params.severity,
    serviceId: params.serviceId,
    from: params.from,
    to: params.to,
    page: params.page,
    size: params.size,
    sort: params.sort,
  });
  return apiRequest<PageResponse<IncidentSummary>>(`/api/v1/incidents${query}`);
}

export function createIncident(request: CreateIncidentRequest): Promise<IncidentDetail> {
  return apiRequest<IncidentDetail>('/api/v1/incidents', { method: 'POST', body: request });
}

export function getIncident(id: string): Promise<IncidentDetail> {
  return apiRequest<IncidentDetail>(`/api/v1/incidents/${id}`);
}

export function getIncidentActivity(
  id: string,
  page = 0,
  size = 50,
): Promise<PageResponse<IncidentActivityEntry>> {
  return apiRequest<PageResponse<IncidentActivityEntry>>(
    `/api/v1/incidents/${id}/activity${buildQuery({ page, size })}`,
  );
}

export function transitionIncident(id: string, request: TransitionIncidentRequest): Promise<IncidentDetail> {
  return apiRequest<IncidentDetail>(`/api/v1/incidents/${id}/status`, { method: 'PATCH', body: request });
}

export function assignIncident(id: string, request: AssignIncidentRequest): Promise<IncidentDetail> {
  return apiRequest<IncidentDetail>(`/api/v1/incidents/${id}/assign`, { method: 'PATCH', body: request });
}
