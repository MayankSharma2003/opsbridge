import { useId, useState } from 'react';
import { observer } from 'mobx-react-lite';
import { useTranslation } from 'react-i18next';
import { useIncidentStore } from '../hooks/useStores';
import { FormDialog } from './FormDialog';
import { Field } from './Field';
import styles from './FormDialog.module.css';
import type { Priority, Severity } from '../types/incident';
import type { ServiceSummary } from '../types/service';

const PRIORITIES: Priority[] = ['P1', 'P2', 'P3', 'P4'];
const SEVERITIES: Severity[] = ['SEV1', 'SEV2', 'SEV3', 'SEV4'];

// Validation here mirrors CreateIncidentRequest but only to save a round trip -- the server
// rejects a bad body regardless, surfacing through the store's mutationError.
export const IncidentFormDialog = observer(function IncidentFormDialog({
  services,
  onClose,
  onCreated,
}: {
  services: ServiceSummary[];
  onClose: () => void;
  onCreated: (id: string) => void;
}) {
  const store = useIncidentStore();
  const { t } = useTranslation();
  const titleId = useId();
  const descriptionId = useId();
  const serviceId = useId();
  const priorityId = useId();
  const severityId = useId();

  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [service, setService] = useState('');
  const [priority, setPriority] = useState<Priority>('P3');
  const [severity, setSeverity] = useState<Severity>('SEV3');
  const [fieldErrors, setFieldErrors] = useState<{ title?: string; service?: string }>({});

  async function submit() {
    const errors: { title?: string; service?: string } = {};
    if (!title.trim()) errors.title = 'pages.incidents.titleRequired';
    if (!service) errors.service = 'pages.incidents.serviceRequired';
    setFieldErrors(errors);
    if (Object.keys(errors).length > 0) return;

    const created = await store.create({
      title: title.trim(),
      description: description.trim() || undefined,
      serviceId: service,
      priority,
      severity,
    });
    if (created) onCreated(created.id);
  }

  return (
    <FormDialog
      title={t('pages.incidents.declareTitle')}
      submitLabel={store.mutationPending ? t('pages.incidents.declareSubmitting') : t('pages.incidents.declareSubmit')}
      pending={store.mutationPending}
      error={store.mutationError}
      onSubmit={() => void submit()}
      onClose={onClose}
    >
      <Field
        label={t('pages.incidents.fieldTitle')}
        htmlFor={titleId}
        error={fieldErrors.title && t(fieldErrors.title)}
      >
        <input
          id={titleId}
          className={styles.input}
          value={title}
          onChange={(event) => setTitle(event.target.value)}
          placeholder={t('pages.incidents.fieldTitlePlaceholder')}
        />
      </Field>

      <Field label={t('pages.incidents.fieldDescription')} htmlFor={descriptionId}>
        <textarea
          id={descriptionId}
          className={styles.textarea}
          value={description}
          onChange={(event) => setDescription(event.target.value)}
          placeholder={t('pages.incidents.fieldDescriptionPlaceholder')}
        />
      </Field>

      <Field
        label={t('pages.incidents.fieldService')}
        htmlFor={serviceId}
        error={fieldErrors.service && t(fieldErrors.service)}
      >
        <select
          id={serviceId}
          className={styles.select}
          value={service}
          onChange={(event) => setService(event.target.value)}
        >
          <option value="">{t('pages.incidents.fieldService')}</option>
          {services.map((option) => (
            <option key={option.id} value={option.id}>
              {option.name}
            </option>
          ))}
        </select>
      </Field>

      <div className={styles.row}>
        <Field label={t('pages.incidents.fieldPriority')} htmlFor={priorityId}>
          <select
            id={priorityId}
            className={styles.select}
            value={priority}
            onChange={(event) => setPriority(event.target.value as Priority)}
          >
            {PRIORITIES.map((value) => (
              <option key={value} value={value}>
                {t(`priority.${value}`)}
              </option>
            ))}
          </select>
        </Field>

        <Field label={t('pages.incidents.fieldSeverity')} htmlFor={severityId}>
          <select
            id={severityId}
            className={styles.select}
            value={severity}
            onChange={(event) => setSeverity(event.target.value as Severity)}
          >
            {SEVERITIES.map((value) => (
              <option key={value} value={value}>
                {t(`severity.${value}`)}
              </option>
            ))}
          </select>
        </Field>
      </div>
    </FormDialog>
  );
});
