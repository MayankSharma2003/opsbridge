import { useTranslation } from 'react-i18next';
import type { Priority } from '../types/incident';
import styles from './badges.module.css';

const PRIORITY_CLASS: Record<Priority, string> = {
  P1: 'crit-fill',
  P2: 'crit-outline',
  P3: 'risk-outline',
  P4: 'neutral-outline',
};

export function PriorityBadge({ priority }: { priority: Priority }) {
  const { t } = useTranslation();
  return <span className={`${styles.chip} ${styles[PRIORITY_CLASS[priority]]}`}>{t(`priority.${priority}`)}</span>;
}
