import { useEffect, useState } from 'react';
import { observer } from 'mobx-react-lite';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { useAuthStore, useIncidentStore } from '../hooks/useStores';
import { listServices } from '../api/serviceApi';
import { PageHeader } from '../components/PageHeader';
import { AsyncState } from '../components/AsyncState';
import { StatusBadge } from '../components/StatusBadge';
import { PriorityBadge } from '../components/PriorityBadge';
import { IncidentFormDialog } from '../components/IncidentFormDialog';
import { formatCountdown } from '../utils/time';
import { initialsFor } from '../utils/initials';
import type { IncidentStatus, Priority } from '../types/incident';
import type { ServiceSummary } from '../types/service';
import styles from './IncidentsPage.module.css';

const STATUSES: IncidentStatus[] = ['OPEN', 'TRIAGED', 'INVESTIGATING', 'MITIGATED', 'RESOLVED', 'CANCELLED'];
const PRIORITIES: Priority[] = ['P1', 'P2', 'P3', 'P4'];
// Beyond this many pages, numbered buttons would outgrow the toolbar; Prev/Next plus the count line cover it instead.
const MAX_NUMBERED_PAGES = 7;

export const IncidentsPage = observer(function IncidentsPage() {
  const store = useIncidentStore();
  const authStore = useAuthStore();
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [services, setServices] = useState<ServiceSummary[]>([]);
  const [declaring, setDeclaring] = useState(false);
  // Hiding the button is UX only -- POST /incidents enforces OPERATOR_OR_ADMIN server-side.
  const canDeclare = authStore.role === 'OPERATOR' || authStore.role === 'ADMIN';

  useEffect(() => {
    void store.fetchList();
    listServices({ size: 100, sort: 'name,asc' })
      .then((result) => setServices(result.content))
      .catch(() => setServices([]));
    // Only runs on mount; filter/page changes go through the store's own setters.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const activePriorities = store.filters.priority ? [store.filters.priority] : [];

  return (
    <>
      <PageHeader
        title={t('pages.incidents.title')}
        description={
          store.listStatus !== 'loaded'
            ? undefined
            : store.isSinglePage
              ? t('pages.incidents.countLineBreakdown', {
                  count: store.totalElements,
                  open: store.openCount,
                  breached: store.breachedCount,
                })
              : // Past one page, open/breached counts only describe the rows in hand, not the whole result set.
                t('pages.incidents.countLine', { count: store.totalElements })
        }
        actions={
          <>
            <input
              className={styles.search}
              value={store.filters.q ?? ''}
              onChange={(event) => store.setFilters({ q: event.target.value || undefined })}
              placeholder={t('pages.incidents.searchPlaceholder')}
            />
            {canDeclare && (
              <button type="button" className={styles.declareButton} onClick={() => setDeclaring(true)}>
                {t('pages.incidents.declare')}
              </button>
            )}
          </>
        }
      />

      {declaring && (
        <IncidentFormDialog
          services={services}
          onClose={() => setDeclaring(false)}
          onCreated={(id) => {
            setDeclaring(false);
            navigate(`/incidents/${id}`);
          }}
        />
      )}

      <div className={styles.filterBar}>
        <div className={styles.filterGroup}>
          <span className={styles.filterLabel}>{t('pages.incidents.statusLabel')}</span>
          <div className={styles.segments}>
            <button
              type="button"
              className={!store.filters.status ? `${styles.segment} ${styles.segmentActive}` : styles.segment}
              onClick={() => store.setFilters({ status: undefined })}
            >
              {t('common.all')}
            </button>
            {STATUSES.map((status) => (
              <button
                key={status}
                type="button"
                className={
                  store.filters.status === status ? `${styles.segment} ${styles.segmentActive}` : styles.segment
                }
                onClick={() => store.setFilters({ status })}
              >
                {t(`status.${status}`)}
              </button>
            ))}
          </div>
        </div>

        <div className={styles.filterGroup}>
          <span className={styles.filterLabel}>{t('pages.incidents.priorityLabel')}</span>
          <div className={styles.priorityChips}>
            {PRIORITIES.map((priority) => (
              <button
                key={priority}
                type="button"
                className={
                  activePriorities.includes(priority)
                    ? `${styles.chipToggle} ${styles.chipToggleActive}`
                    : styles.chipToggle
                }
                onClick={() =>
                  store.setFilters({ priority: store.filters.priority === priority ? undefined : priority })
                }
              >
                {t(`priority.${priority}`)}
              </button>
            ))}
          </div>
        </div>

        <div className={styles.filterGroup}>
          <span className={styles.filterLabel}>{t('pages.incidents.serviceLabel')}</span>
          <select
            className={styles.select}
            value={store.filters.serviceId ?? ''}
            onChange={(event) => store.setFilters({ serviceId: event.target.value || undefined })}
          >
            <option value="">{t('pages.incidents.allServices')}</option>
            {services.map((service) => (
              <option key={service.id} value={service.id}>
                {service.name}
              </option>
            ))}
          </select>
        </div>

        <button type="button" className={styles.clearButton} onClick={() => store.clearFilters()}>
          {t('common.clearFilters')}
        </button>
      </div>

      <div className={styles.tableCard}>
        {store.listStatus === 'loading' && <AsyncState role="status">{t('common.loading')}</AsyncState>}

        {store.listStatus === 'error' && (
          <AsyncState
            role="alert"
            title={t('pages.incidents.errorTitle')}
            description={store.listError && t(store.listError)}
            action={
              <button type="button" className={styles.clearButton} onClick={() => void store.fetchList()}>
                {t('common.retry')}
              </button>
            }
          />
        )}

        {store.isListEmpty && (
          <AsyncState
            title={t('pages.incidents.emptyTitle')}
            description={t('pages.incidents.emptyDescription')}
            action={
              <button type="button" className={styles.clearButton} onClick={() => store.clearFilters()}>
                {t('common.clearFilters')}
              </button>
            }
          />
        )}

        {store.listStatus === 'loaded' && store.items.length > 0 && (
          <>
            <table className={styles.table}>
              <thead>
                <tr>
                  <th>{t('pages.incidents.columnId')}</th>
                  <th>{t('pages.incidents.columnTitle')}</th>
                  <th>{t('pages.incidents.columnService')}</th>
                  <th>{t('pages.incidents.columnPriority')}</th>
                  <th>{t('pages.incidents.columnStatus')}</th>
                  <th>{t('pages.incidents.columnSla')}</th>
                  <th>{t('pages.incidents.columnAssignee')}</th>
                </tr>
              </thead>
              <tbody>
                {store.items.map((incident) => (
                  <tr
                    key={incident.id}
                    className={
                      incident.slaState === 'BREACHED' ? `${styles.row} ${styles.rowBreached}` : styles.row
                    }
                    onClick={() => navigate(`/incidents/${incident.id}`)}
                  >
                    <td className={styles.mono}>{incident.publicKey}</td>
                    <td className={styles.title}>{incident.title}</td>
                    <td className={styles.mono}>{incident.service.name}</td>
                    <td>
                      <PriorityBadge priority={incident.priority} />
                    </td>
                    <td>
                      <StatusBadge status={incident.status} />
                    </td>
                    <td>{renderSla(incident.slaState, incident.slaResolutionTargetAt, t, styles)}</td>
                    <td>
                      <span className={styles.assignee}>
                        <span
                          className={
                            incident.assignee
                              ? styles.avatar
                              : `${styles.avatar} ${styles.avatarUnassigned}`
                          }
                        >
                          {initialsFor(incident.assignee?.name)}
                        </span>
                        {incident.assignee?.name ?? t('common.unassigned')}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>

            <div className={styles.pagination}>
              <span>
                {t('common.pageOf', {
                  from: store.page * store.size + 1,
                  to: Math.min((store.page + 1) * store.size, store.totalElements),
                  total: store.totalElements,
                })}
              </span>
              <div className={styles.pageButtons}>
                <button
                  type="button"
                  className={styles.pageButton}
                  disabled={store.page === 0}
                  onClick={() => store.goToPage(store.page - 1)}
                >
                  {t('common.prev')}
                </button>
                {store.totalPages > 1 &&
                  store.totalPages <= MAX_NUMBERED_PAGES &&
                  Array.from({ length: store.totalPages }, (_, pageIndex) => (
                    <button
                      key={pageIndex}
                      type="button"
                      aria-current={pageIndex === store.page ? 'page' : undefined}
                      className={
                        pageIndex === store.page
                          ? `${styles.pageButton} ${styles.pageButtonActive}`
                          : styles.pageButton
                      }
                      onClick={() => store.goToPage(pageIndex)}
                    >
                      {pageIndex + 1}
                    </button>
                  ))}
                <button
                  type="button"
                  className={styles.pageButton}
                  disabled={store.page + 1 >= store.totalPages}
                  onClick={() => store.goToPage(store.page + 1)}
                >
                  {t('common.next')}
                </button>
              </div>
            </div>
          </>
        )}
      </div>
    </>
  );
});

function renderSla(
  slaState: string,
  targetAt: string | null,
  t: (key: string) => string,
  styles: Record<string, string>,
) {
  if (slaState === 'BREACHED') {
    return <span className={styles.slaBreached}>{t('pages.incidents.slaBreached')}</span>;
  }
  if (slaState === 'MET') {
    return <span className={styles.slaMet}>{t('pages.incidents.slaMet')}</span>;
  }
  const countdown = formatCountdown(targetAt);
  return <span className={styles.slaOnTrack}>{countdown ?? '—'}</span>;
}
