import { apiRequest } from './client';
import type { PageResponse } from '../types/pagination';
import type { Role } from '../types/auth';
import type { UserSummary } from '../types/user';

export interface UserSearchParams {
  role?: Role[];
  page?: number;
  size?: number;
}

// uses .append, not buildQuery's set-per-key, so repeated ?role= entries bind as a set
export function listUsers(params: UserSearchParams): Promise<PageResponse<UserSummary>> {
  const search = new URLSearchParams();
  for (const role of params.role ?? []) {
    search.append('role', role);
  }
  if (params.page !== undefined) search.set('page', String(params.page));
  if (params.size !== undefined) search.set('size', String(params.size));
  const query = search.toString();
  return apiRequest<PageResponse<UserSummary>>(`/api/v1/users${query ? `?${query}` : ''}`);
}
