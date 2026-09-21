import type { Priority } from './incident';

export interface DashboardKpis {
  openIncidents: number;
  unassignedIncidents: number;
  slaBreachesThisWeek: number;
  mttrMinutes: number | null;
}

export interface PriorityCount {
  priority: Priority;
  count: number;
}

export interface MttrTrendPoint {
  date: string;
  mttrMinutes: number | null;
}

export interface DashboardSummary {
  kpis: DashboardKpis;
  priorityDistribution: PriorityCount[];
  mttrTrend: MttrTrendPoint[];
}
