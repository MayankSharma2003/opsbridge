import { useTranslation } from 'react-i18next';
import { Badge } from './Badge';
import type { Kind } from '../utils/statusKind';
import type { ServiceStatus } from '../types/service';

const SERVICE_STATUS_KIND: Record<ServiceStatus, Kind> = { ACTIVE: 'ok', ARCHIVED: 'neutral' };

export function ServiceStatusBadge({ status }: { status: ServiceStatus }) {
  const { t } = useTranslation();
  return <Badge kind={SERVICE_STATUS_KIND[status]}>{t(`serviceStatus.${status}`)}</Badge>;
}
