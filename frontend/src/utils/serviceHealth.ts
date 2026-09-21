import type { Kind } from './statusKind';

/** The catalog's health signal, in the same colour vocabulary as the status badges. */
export type Health = Extract<Kind, 'crit' | 'risk' | 'ok'>;

/**
 * A service's health, from how much is open against it and how urgent that work is. Shared by the
 * catalog table and the drawer so one service can never show two different signals on one screen.
 *
 * <p>Both counts come from the server (ServiceResponse), which is what makes this correct for a
 * whole page of services: deriving them from a fetched incident page would undercount any service
 * whose open work falls outside that page, and quietly report a real outage as nominal.
 */
export function healthFor(openIncidentCount: number, urgentOpenIncidentCount: number): Health {
  if (urgentOpenIncidentCount > 0) return 'crit';
  if (openIncidentCount > 0) return 'risk';
  return 'ok';
}

export function healthLabelKey(health: Health): string {
  if (health === 'crit') return 'pages.services.healthCritical';
  if (health === 'risk') return 'pages.services.healthWatching';
  return 'pages.services.healthNominal';
}
