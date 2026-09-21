import type { ReactNode } from 'react';
import type { Kind } from '../utils/statusKind';
import styles from './badges.module.css';

export function Badge({ kind, children }: { kind: Kind; children: ReactNode }) {
  return <span className={`${styles.pill} ${styles[kind]}`}>{children}</span>;
}
