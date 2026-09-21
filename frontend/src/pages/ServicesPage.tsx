import { useEffect, useState } from 'react';
import { observer } from 'mobx-react-lite';
import { useTranslation } from 'react-i18next';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useAuthStore, useServiceStore } from '../hooks/useStores';
import { PageHeader } from '../components/PageHeader';
import { AsyncState } from '../components/AsyncState';
import { StatusBadge } from '../components/StatusBadge';
import { PriorityBadge } from '../components/PriorityBadge';
import { Badge } from '../components/Badge';
import { ServiceHealthBadge } from '../components/ServiceHealthBadge';
import { ServiceStatusBadge } from '../components/ServiceStatusBadge';
import { ServiceFormDialog } from '../components/ServiceFormDialog';
import type { ServiceStatus } from '../types/service';
import styles from './ServicesPage.module.css';

const STATUSES: ServiceStatus[] = ['ACTIVE', 'ARCHIVED'];

export const ServicesPage = observer(function ServicesPage() {
  const store = useServiceStore();
  const authStore = useAuthStore();
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const openId = searchParams.get('open');
  const [formMode, setFormMode] = useState<'create' | 'edit' | null>(null);
  // Hiding these is UX only -- the catalog endpoints enforce ADMIN server-side.
  const canManage = authStore.role === 'ADMIN';

  useEffect(() => {
    void store.fetchList();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (openId) {
      void store.selectService(openId);
    } else {
      store.clearSelection();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [openId]);

  function openDrawer(id: string) {
    setSearchParams((params) => {
      const next = new URLSearchParams(params);
      next.set('open', id);
      return next;
    });
  }

  function closeDrawer() {
    setSearchParams((params) => {
      const next = new URLSearchParams(params);
      next.delete('open');
      return next;
    });
  }

  return (
    <>
      <PageHeader
        title={t('pages.services.title')}
        description={
          store.listStatus === 'loaded' ? t('pages.services.countLine', { count: store.totalElements }) : undefined
        }
        actions={
          <>
            <input
              className={styles.search}
              value={store.filters.q ?? ''}
              onChange={(event) => store.setFilters({ q: event.target.value || undefined })}
              placeholder={t('pages.services.searchPlaceholder')}
            />
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
                  {t(`serviceStatus.${status}`)}
                </button>
              ))}
            </div>
            <button type="button" className={styles.clearButton} onClick={() => store.clearFilters()}>
              {t('common.clearFilters')}
            </button>
            {canManage && (
              <button type="button" className={styles.addButton} onClick={() => setFormMode('create')}>
                {t('pages.services.addService')}
              </button>
            )}
          </>
        }
      />

      {formMode && (
        <ServiceFormDialog
          service={formMode === 'edit' && store.selected ? store.selected : undefined}
          onClose={() => setFormMode(null)}
          onSaved={() => setFormMode(null)}
        />
      )}

      <div className={styles.tableCard}>
        {store.listStatus === 'loading' && <AsyncState role="status">{t('common.loading')}</AsyncState>}

        {store.listStatus === 'error' && (
          <AsyncState
            role="alert"
            title={t('pages.services.errorTitle')}
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
            title={t('pages.services.emptyTitle')}
            description={t('pages.services.emptyDescription')}
            action={
              <button type="button" className={styles.clearButton} onClick={() => store.clearFilters()}>
                {t('common.clearFilters')}
              </button>
            }
          />
        )}

        {store.listStatus === 'loaded' && store.items.length > 0 && (
          <table className={styles.table}>
            <thead>
              <tr>
                <th>{t('pages.services.columnService')}</th>
                <th>{t('pages.services.columnTeam')}</th>
                <th>{t('pages.services.columnStatus')}</th>
                <th>{t('pages.services.columnHealth')}</th>
                <th>{t('pages.services.columnActiveIncidents')}</th>
                <th>{t('pages.services.columnAvailability')}</th>
              </tr>
            </thead>
            <tbody>
              {store.items.map((service) => (
                <tr key={service.id} className={styles.row} onClick={() => openDrawer(service.id)}>
                  <td>
                    <div className={styles.serviceCell}>
                      <span className={styles.serviceIcon} aria-hidden="true">
                        {service.name.slice(0, 2).toLowerCase()}
                      </span>
                      <span className={styles.serviceName}>{service.name}</span>
                    </div>
                  </td>
                  <td>{service.team ?? '—'}</td>
                  <td>{t(`serviceStatus.${service.status}`)}</td>
                  <td>
                    <ServiceHealthBadge
                      openIncidentCount={service.openIncidentCount}
                      urgentOpenIncidentCount={service.urgentOpenIncidentCount}
                    />
                  </td>
                  <td className={styles.mono}>{service.openIncidentCount}</td>
                  <td>{service.availabilityTarget ? `${service.availabilityTarget}%` : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {store.listStatus === 'loaded' && store.items.length > 0 && (
        <div className={styles.hint}>{t('pages.services.drawerHint')}</div>
      )}

      {openId && (
        <div>
          <div className={styles.scrim} onClick={closeDrawer} />
          <div className={styles.drawer}>
            <div className={styles.drawerHeader}>
              <div className={styles.drawerHeaderTop}>
                <span className={styles.drawerKicker}>{t('pages.services.columnService')}</span>
                <button type="button" className={styles.closeButton} aria-label={t('common.close')} onClick={closeDrawer}>
                  ✕
                </button>
              </div>

              {store.selectedStatus === 'loading' && <div role="status">{t('common.loading')}</div>}
              {store.selectedStatus === 'error' && <div role="alert">{t('pages.services.notFoundTitle')}</div>}

              {store.selected && (
                <>
                  <div className={styles.drawerTitleRow}>
                    <span className={styles.drawerIcon} aria-hidden="true">
                      {store.selected.name.slice(0, 2).toLowerCase()}
                    </span>
                    <div>
                      <div className={styles.drawerName}>{store.selected.name}</div>
                      <div className={styles.drawerMeta}>{store.selected.team ?? '—'}</div>
                    </div>
                  </div>
                  <div className={styles.drawerBadges}>
                    <ServiceStatusBadge status={store.selected.status} />
                    <ServiceHealthBadge
                      openIncidentCount={store.selected.openIncidentCount}
                      urgentOpenIncidentCount={store.selected.urgentOpenIncidentCount}
                    />
                    <Badge kind="neutral">
                      {t('pages.services.openIncidents', { count: store.selected.openIncidentCount })}
                    </Badge>
                  </div>
                </>
              )}
            </div>

            {store.selected && (
              <div className={styles.drawerBody}>
                <div className={styles.statGrid}>
                  <div className={styles.statTile}>
                    <div className={styles.statLabel}>{t('pages.services.columnAvailability')}</div>
                    <div className={styles.statValue}>
                      {store.selected.availabilityTarget ? `${store.selected.availabilityTarget}%` : '—'}
                    </div>
                  </div>
                  <div className={styles.statTile}>
                    <div className={styles.statLabel}>{t('pages.incidentDetail.escalationContact')}</div>
                    <div className={styles.statValue}>{store.selected.escalationContact ?? '—'}</div>
                  </div>
                </div>

                <div>
                  <div className={styles.sectionLabel}>{t('pages.incidentDetail.description')}</div>
                  <div className={styles.description}>
                    {store.selected.description || t('pages.incidentDetail.noDescription')}
                  </div>
                </div>

                <div>
                  <div className={styles.sectionLabel}>{t('pages.services.recentIncidents')}</div>
                  {store.relatedIncidentsStatus === 'loading' && <div role="status">{t('common.loading')}</div>}
                  {store.relatedIncidentsStatus === 'loaded' && store.relatedIncidents.length === 0 && (
                    <div className={styles.description}>{t('pages.services.noRecentIncidents')}</div>
                  )}
                  <div className={styles.incidentList}>
                    {store.relatedIncidents.slice(0, 5).map((incident) => (
                      <div
                        key={incident.id}
                        className={styles.incidentRow}
                        onClick={() => navigate(`/incidents/${incident.id}`)}
                      >
                        <span className={styles.incidentId}>{incident.publicKey}</span>
                        <span className={styles.incidentTitle}>{incident.title}</span>
                        <PriorityBadge priority={incident.priority} />
                        <StatusBadge status={incident.status} />
                      </div>
                    ))}
                  </div>
                </div>
              </div>
            )}

            <div className={styles.drawerFooter}>
              {canManage && store.selected && (
                <button type="button" className={styles.editButton} onClick={() => setFormMode('edit')}>
                  {t('pages.services.editService')}
                </button>
              )}
              <button type="button" className={styles.footerClose} onClick={closeDrawer}>
                {t('common.close')}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
});
