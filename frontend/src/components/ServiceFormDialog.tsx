import { useEffect, useId, useState } from 'react';
import { observer } from 'mobx-react-lite';
import { useTranslation } from 'react-i18next';
import { useServiceStore } from '../hooks/useStores';
import { listUsers } from '../api/userApi';
import { FormDialog } from './FormDialog';
import { Field } from './Field';
import styles from './FormDialog.module.css';
import type { ServiceDetail } from '../types/service';
import type { UserSummary } from '../types/user';

// Admin-only create/edit form; `service` present means edit, absent means create. Validation here
// mirrors CreateServiceRequest just to save a round trip -- the backend enforces both the rules
// and the ADMIN policy independently.
export const ServiceFormDialog = observer(function ServiceFormDialog({
  service,
  onClose,
  onSaved,
}: {
  service?: ServiceDetail;
  onClose: () => void;
  onSaved: () => void;
}) {
  const store = useServiceStore();
  const { t } = useTranslation();
  const nameId = useId();
  const descriptionId = useId();
  const ownerId = useId();
  const teamId = useId();
  const escalationId = useId();
  const availabilityId = useId();

  const [owners, setOwners] = useState<UserSummary[]>([]);
  const [name, setName] = useState(service?.name ?? '');
  const [description, setDescription] = useState(service?.description ?? '');
  const [owner, setOwner] = useState(service?.owner.id ?? '');
  const [team, setTeam] = useState(service?.team ?? '');
  const [escalationContact, setEscalationContact] = useState(service?.escalationContact ?? '');
  // kept as text so a half-typed "99." is editable, parsed to a number on submit
  const [availabilityTarget, setAvailabilityTarget] = useState(
    service?.availabilityTarget != null ? String(service.availabilityTarget) : '',
  );
  const [fieldErrors, setFieldErrors] = useState<{ name?: string; owner?: string; availabilityTarget?: string }>({});

  useEffect(() => {
    listUsers({ role: ['OPERATOR', 'ADMIN'], size: 100 })
      .then((result) => setOwners(result.content))
      .catch(() => setOwners([]));
  }, []);

  async function submit() {
    const errors: { name?: string; owner?: string; availabilityTarget?: string } = {};
    if (!name.trim()) errors.name = 'pages.services.nameRequired';
    if (!owner) errors.owner = 'pages.services.ownerRequired';
    setFieldErrors(errors);
    if (Object.keys(errors).length > 0) return;

    const trimmedAvailability = availabilityTarget.trim();
    const parsedAvailability = trimmedAvailability === '' ? undefined : Number(trimmedAvailability);
    if (parsedAvailability !== undefined && Number.isNaN(parsedAvailability)) {
      setFieldErrors({ ...errors, availabilityTarget: 'pages.services.availabilityInvalid' });
      return;
    }

    const payload = {
      name: name.trim(),
      description: description.trim() || undefined,
      ownerId: owner,
      team: team.trim() || undefined,
      escalationContact: escalationContact.trim() || undefined,
      availabilityTarget: parsedAvailability,
    };
    const saved = service ? await store.update(service.id, payload) : await store.create(payload);
    if (saved) onSaved();
  }

  return (
    <FormDialog
      title={service ? t('pages.services.editTitle') : t('pages.services.addTitle')}
      submitLabel={store.mutationPending ? t('pages.services.savingService') : t('pages.services.saveService')}
      pending={store.mutationPending}
      error={store.mutationError}
      onSubmit={() => void submit()}
      onClose={onClose}
    >
      <Field
        label={t('pages.services.fieldName')}
        htmlFor={nameId}
        error={fieldErrors.name && t(fieldErrors.name)}
      >
        <input
          id={nameId}
          className={styles.input}
          value={name}
          onChange={(event) => setName(event.target.value)}
          placeholder={t('pages.services.fieldNamePlaceholder')}
        />
      </Field>

      <Field label={t('pages.services.fieldDescription')} htmlFor={descriptionId}>
        <textarea
          id={descriptionId}
          className={styles.textarea}
          value={description}
          onChange={(event) => setDescription(event.target.value)}
          placeholder={t('pages.services.fieldDescriptionPlaceholder')}
        />
      </Field>

      <Field
        label={t('pages.services.fieldOwner')}
        htmlFor={ownerId}
        error={fieldErrors.owner && t(fieldErrors.owner)}
      >
        <select
          id={ownerId}
          className={styles.select}
          value={owner}
          onChange={(event) => setOwner(event.target.value)}
        >
          <option value="">{t('pages.services.fieldOwner')}</option>
          {owners.map((person) => (
            <option key={person.id} value={person.id}>
              {person.name}
            </option>
          ))}
        </select>
      </Field>

      <div className={styles.row}>
        <Field label={t('pages.services.fieldTeam')} htmlFor={teamId}>
          <input
            id={teamId}
            className={styles.input}
            value={team}
            onChange={(event) => setTeam(event.target.value)}
          />
        </Field>

        <Field
          label={t('pages.services.fieldAvailability')}
          htmlFor={availabilityId}
          error={fieldErrors.availabilityTarget && t(fieldErrors.availabilityTarget)}
        >
          <input
            id={availabilityId}
            className={styles.input}
            value={availabilityTarget}
            onChange={(event) => setAvailabilityTarget(event.target.value)}
            inputMode="decimal"
          />
        </Field>
      </div>

      <Field label={t('pages.services.fieldEscalation')} htmlFor={escalationId}>
        <input
          id={escalationId}
          className={styles.input}
          value={escalationContact}
          onChange={(event) => setEscalationContact(event.target.value)}
        />
      </Field>
    </FormDialog>
  );
});
