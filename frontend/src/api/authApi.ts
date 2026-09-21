import type { Role } from '../types/auth';
import { apiRequest } from './client';

export interface LoginResponse {
  token: string;
  tokenType: string;
  expiresAt: string;
  userId: string;
  name: string;
  email: string;
  role: Role;
}

export interface MeResponse {
  id: string;
  name: string;
  email: string;
  role: Role;
}

export function login(email: string, password: string): Promise<LoginResponse> {
  return apiRequest<LoginResponse>('/api/v1/auth/login', { method: 'POST', body: { email, password } });
}

export function me(): Promise<MeResponse> {
  return apiRequest<MeResponse>('/api/v1/me');
}
