import { useTranslation } from 'react-i18next';
import { Badge } from './Badge';
import { healthFor, healthLabelKey } from '../utils/serviceHealth';

export function ServiceHealthBadge({
  openIncidentCount,
  urgentOpenIncidentCount,
}: {
  openIncidentCount: number;
  urgentOpenIncidentCount: number;
}) {
  const { t } = useTranslation();
  const health = healthFor(openIncidentCount, urgentOpenIncidentCount);
  return <Badge kind={health}>{t(healthLabelKey(health))}</Badge>;
}
