import type { ReactNode } from 'react';
import styles from './PageHeader.module.css';

// each screen owns its own topbar (see AppShell); `actions` is right-aligned, where the
// prototypes put a screen's search box
export function PageHeader({
  title,
  description,
  actions,
}: {
  title: string;
  description?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <header className={styles.header}>
      <div>
        <h1 className={styles.title}>{title}</h1>
        {description && <div className={styles.description}>{description}</div>}
      </div>
      {actions && <div className={styles.actions}>{actions}</div>}
    </header>
  );
}
