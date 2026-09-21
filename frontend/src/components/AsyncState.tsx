import type { ReactNode } from 'react';
import styles from './AsyncState.module.css';

// Centered loading/error/empty block shared by list and summary screens. `role` matches the
// caller's need ("status" for loading, "alert" for error); retry/clear actions stay caller-owned.
export function AsyncState({
  role,
  title,
  description,
  action,
  children,
}: {
  role?: 'status' | 'alert';
  title?: ReactNode;
  description?: ReactNode;
  action?: ReactNode;
  children?: ReactNode;
}) {
  return (
    <div className={styles.stateBlock} role={role}>
      {children}
      {title !== undefined && <div className={styles.stateTitle}>{title}</div>}
      {description !== undefined && <div className={styles.stateDescription}>{description}</div>}
      {action}
    </div>
  );
}
