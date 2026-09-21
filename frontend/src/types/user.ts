import type { Role } from './auth';

export interface UserSummary {
  id: string;
  name: string;
  email: string;
  role: Role;
}
