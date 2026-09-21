import { useTranslation } from 'react-i18next';
import type { IncidentStatus } from '../types/incident';
import { STATUS_KIND } from '../utils/statusKind';
import styles from './badges.module.css';

export function StatusBadge({ status }: { status: IncidentStatus }) {
  const { t } = useTranslation();
  const kind = STATUS_KIND[status];
  return (
    <span className={`${styles.pill} ${styles[kind]}`}>
      <span className={`${styles.dot} ${styles[`dot-${kind}`]}`} />
      {t(`status.${status}`)}
    </span>
  );
}
