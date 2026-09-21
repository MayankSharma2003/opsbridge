import { apiRequest, buildQuery } from './client';
import type { PageResponse } from '../types/pagination';
import type {
  CreateServiceRequest,
  ServiceDetail,
  ServiceStatus,
  UpdateServiceRequest,
} from '../types/service';

export interface ServiceSearchParams {
  q?: string;
  status?: ServiceStatus;
  team?: string;
  page?: number;
  size?: number;
  sort?: string;
}

export function listServices(params: ServiceSearchParams): Promise<PageResponse<ServiceDetail>> {
  const query = buildQuery({
    q: params.q,
    status: params.status,
    team: params.team,
    page: params.page,
    size: params.size,
    sort: params.sort,
  });
  return apiRequest<PageResponse<ServiceDetail>>(`/api/v1/services${query}`);
}

export function getService(id: string): Promise<ServiceDetail> {
  return apiRequest<ServiceDetail>(`/api/v1/services/${id}`);
}

export function createService(request: CreateServiceRequest): Promise<ServiceDetail> {
  return apiRequest<ServiceDetail>('/api/v1/services', { method: 'POST', body: request });
}

export function updateService(id: string, request: UpdateServiceRequest): Promise<ServiceDetail> {
  return apiRequest<ServiceDetail>(`/api/v1/services/${id}`, { method: 'PATCH', body: request });
}
