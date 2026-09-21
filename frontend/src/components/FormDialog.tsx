import { useEffect, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import styles from './FormDialog.module.css';

// Modal shell shared by create/edit forms: scrim, header, footer, Escape to dismiss. Owns no form
// state -- callers keep their own fields, so this stays a layout concern, not a business-rules spot.
export function FormDialog({
  title,
  submitLabel,
  pending,
  error,
  onSubmit,
  onClose,
  children,
}: {
  title: string;
  submitLabel: string;
  pending: boolean;
  error?: string | null;
  onSubmit: () => void;
  onClose: () => void;
  children: ReactNode;
}) {
  const { t } = useTranslation();

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [onClose]);

  return (
    <div className={styles.scrim} onClick={onClose}>
      <div
        className={styles.dialog}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        // stop propagation or the scrim's click-to-close fires too
        onClick={(event) => event.stopPropagation()}
      >
        <div className={styles.header}>
          <h2 className={styles.title}>{title}</h2>
          <button type="button" className={styles.closeButton} aria-label={t('common.close')} onClick={onClose}>
            ✕
          </button>
        </div>

        <form
          onSubmit={(event) => {
            event.preventDefault();
            onSubmit();
          }}
        >
          <div className={styles.body}>
            {error && (
              <div className={styles.error} role="alert">
                {t(error)}
              </div>
            )}
            {children}
          </div>

          <div className={styles.footer}>
            <button type="button" className={styles.btnSecondary} onClick={onClose}>
              {t('common.cancel')}
            </button>
            <button type="submit" className={styles.btnPrimary} disabled={pending}>
              {submitLabel}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
