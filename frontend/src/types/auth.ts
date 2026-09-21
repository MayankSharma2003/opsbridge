export type Role = 'VIEWER' | 'OPERATOR' | 'ADMIN';

export interface AuthenticatedUser {
  id: string;
  name: string;
  email: string;
  role: Role;
}
