import type { ReactNode } from 'react';
import styles from './FormDialog.module.css';

export function Field({
  label,
  htmlFor,
  error,
  children,
}: {
  label: string;
  htmlFor: string;
  error?: ReactNode;
  children: ReactNode;
}) {
  return (
    <div className={styles.field}>
      <label className={styles.label} htmlFor={htmlFor}>
        {label}
      </label>
      {children}
      {error && <div className={styles.fieldError}>{error}</div>}
    </div>
  );
}
