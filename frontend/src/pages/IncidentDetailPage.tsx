import { useEffect, useState } from 'react';
import { observer } from 'mobx-react-lite';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router-dom';
import { useAuthStore, useIncidentStore } from '../hooks/useStores';
import { listUsers } from '../api/userApi';
import { getService } from '../api/serviceApi';
import { StatusBadge } from '../components/StatusBadge';
import { PriorityBadge } from '../components/PriorityBadge';
import { formatActivityText } from '../utils/formatActivity';
import { formatCountdown, formatRelativeTime, nextOutstandingTarget, slaProgressPercent } from '../utils/time';
import { activityEventKind } from '../utils/statusKind';
import { initialsFor } from '../utils/initials';
import type { IncidentStatus } from '../types/incident';
import type { ServiceDetail } from '../types/service';
import type { UserSummary } from '../types/user';
import styles from './IncidentDetailPage.module.css';
import badgeStyles from '../components/badges.module.css';

const LEGAL_TRANSITIONS: Record<IncidentStatus, IncidentStatus[]> = {
  OPEN: ['TRIAGED', 'CANCELLED'],
  TRIAGED: ['INVESTIGATING', 'CANCELLED'],
  INVESTIGATING: ['MITIGATED', 'OPEN'],
  MITIGATED: ['RESOLVED', 'INVESTIGATING'],
  RESOLVED: [],
  CANCELLED: [],
};

export const IncidentDetailPage = observer(function IncidentDetailPage() {
  const { id } = useParams<{ id: string }>();
  const store = useIncidentStore();
  const authStore = useAuthStore();
  const { t, i18n } = useTranslation();

  const [statusMenuOpen, setStatusMenuOpen] = useState(false);
  const [assignMenuOpen, setAssignMenuOpen] = useState(false);
  const [resolvingWithNotes, setResolvingWithNotes] = useState(false);
  const [resolutionNotes, setResolutionNotes] = useState('');
  const [assignableUsers, setAssignableUsers] = useState<UserSummary[]>([]);
  const [linkedService, setLinkedService] = useState<ServiceDetail | null>(null);
  const [now, setNow] = useState(new Date());

  const canAct = authStore.role === 'OPERATOR' || authStore.role === 'ADMIN';

  useEffect(() => {
    if (id) void store.fetchDetail(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  useEffect(() => {
    listUsers({ role: ['OPERATOR', 'ADMIN'], size: 100 })
      .then((result) => setAssignableUsers(result.content))
      .catch(() => setAssignableUsers([]));
  }, []);

  useEffect(() => {
    const timer = setInterval(() => setNow(new Date()), 1000);
    return () => clearInterval(timer);
  }, []);

  const linkedServiceId = store.detail?.service.id;
  useEffect(() => {
    if (!linkedServiceId) {
      setLinkedService(null);
      return;
    }
    getService(linkedServiceId)
      .then(setLinkedService)
      .catch(() => setLinkedService(null));
  }, [linkedServiceId]);

  if (store.detailStatus === 'loading' || store.detailStatus === 'idle') {
    return (
      <div className={styles.page}>
        <div role="status" style={{ padding: 28 }}>
          {t('common.loading')}
        </div>
      </div>
    );
  }

  if (store.detailStatus === 'error' || !store.detail) {
    return (
      <div className={styles.page}>
        <div role="alert" style={{ padding: 28 }}>
          <div className={styles.cardTitle}>{t('pages.incidentDetail.notFoundTitle')}</div>
          <Link className={styles.breadcrumb} to="/incidents">
            {t('pages.incidentDetail.breadcrumb')}
          </Link>
        </div>
      </div>
    );
  }

  const incident = store.detail;

  async function pickStatus(target: IncidentStatus) {
    setStatusMenuOpen(false);
    if (target === 'RESOLVED') {
      setResolvingWithNotes(true);
      return;
    }
    if (!id) return;
    await store.transition(id, { expectedVersion: incident.version, targetStatus: target });
  }

  async function confirmResolve() {
    if (!id) return;
    const ok = await store.transition(id, {
      expectedVersion: incident.version,
      targetStatus: 'RESOLVED',
      resolutionNotes: resolutionNotes || undefined,
    });
    if (ok) {
      setResolvingWithNotes(false);
      setResolutionNotes('');
    }
  }

  async function pickAssignee(userId: string | null) {
    if (!id) return;
    setAssignMenuOpen(false);
    await store.assign(id, { expectedVersion: incident.version, assigneeId: userId });
  }

  const nextTarget = nextOutstandingTarget(incident.sla);
  const countdown = formatCountdown(nextTarget, now);
  const slaKind = incident.sla.state === 'MET' ? 'ok' : incident.sla.state === 'BREACHED' ? 'crit'
    : incident.sla.state === 'AT_RISK' ? 'risk' : 'info';
  const slaBarPercent = nextTarget ? slaProgressPercent(incident.sla.startedAt, nextTarget, now) : 100;

  return (
    <div className={styles.page}>
      <div className={styles.topbar}>
        <div className={styles.brand}>
          <span className={styles.brandMark} aria-hidden="true">
            ⏳
          </span>
          <Link className={styles.breadcrumb} to="/incidents">
            {t('pages.incidentDetail.breadcrumb')}
          </Link>
          <span className={styles.breadcrumbSep}>/</span>
          <span className={styles.publicKey}>{incident.publicKey}</span>
        </div>
        <div className={styles.who}>
          <span className={authStore.role === 'VIEWER' ? `${styles.roleBadge} ${styles.roleBadgeViewer}` : styles.roleBadge}>
            {authStore.role && t(`role.${authStore.role}`)}
          </span>
          {authStore.user && (
            <>
              <span className={styles.avatar}>{initialsFor(authStore.user.name)}</span>
              <span>{authStore.user.name}</span>
            </>
          )}
        </div>
      </div>

      <div className={styles.summary}>
        <div className={styles.summaryMain}>
          <div className={styles.summaryTop}>
            <StatusBadge status={incident.status} />
            <PriorityBadge priority={incident.priority} />
            <span className={styles.opened} title={incident.createdAt}>
              {t('pages.incidentDetail.openedAgo', { time: formatRelativeTime(incident.createdAt, now, i18n.language) })}
            </span>
          </div>
          <h1 className={styles.title}>{incident.title}</h1>
          <div className={styles.metaLine}>
            <span className={styles.mono}>{incident.service.name}</span>
            {incident.assignee && (
              <>
                <span className={styles.metaSep}>·</span>
                <span>{t('pages.incidentDetail.assignedTo', { name: incident.assignee.name })}</span>
              </>
            )}
          </div>
        </div>

        <div className={styles.slaCard} style={{ borderLeft: `3px solid var(--ob-${slaKind})` }}>
          <div className={styles.slaLabel}>{t('pages.incidentDetail.slaRemaining')}</div>
          <div className={styles.slaTime} style={{ color: `var(--ob-${slaKind}-ink)` }}>
            {incident.sla.state === 'MET' ? t('pages.incidentDetail.slaMet') : (countdown ?? '00:00:00')}
          </div>
          <div className={styles.slaBar}>
            <div
              className={styles.slaBarFill}
              style={{ width: `${slaBarPercent}%`, background: `var(--ob-${slaKind})` }}
            />
          </div>
        </div>

        <div className={styles.actions}>
          <div className={styles.actionWrap}>
            <button
              type="button"
              className={styles.btnPrimary}
              disabled={!canAct}
              onClick={() => {
                setStatusMenuOpen((open) => !open);
                setAssignMenuOpen(false);
              }}
            >
              {t('pages.incidentDetail.changeStatus')} ▾
            </button>
            {statusMenuOpen && canAct && !resolvingWithNotes && (
              <div className={styles.menu}>
                <div className={styles.menuLabel}>
                  {t('pages.incidentDetail.validTransitionsFrom', { status: t(`status.${incident.status}`) })}
                </div>
                {LEGAL_TRANSITIONS[incident.status].map((target) => (
                  <button key={target} type="button" className={styles.menuItem} onClick={() => void pickStatus(target)}>
                    <span className={styles.menuItemRow}>
                      <span className={styles.menuDot} style={{ background: 'var(--ob-info)' }} />
                      <span>{t(`status.${target}`)}</span>
                    </span>
                  </button>
                ))}
              </div>
            )}
            {resolvingWithNotes && (
              <div className={styles.menu}>
                <div className={styles.resolutionForm}>
                  <label className={styles.menuLabel} htmlFor="resolution-notes">
                    {t('pages.incidentDetail.resolutionNotesLabel')}
                  </label>
                  <textarea
                    id="resolution-notes"
                    className={styles.resolutionTextarea}
                    value={resolutionNotes}
                    onChange={(event) => setResolutionNotes(event.target.value)}
                    placeholder={t('pages.incidentDetail.resolutionNotesPlaceholder')}
                  />
                  <button type="button" className={styles.btnPrimary} onClick={() => void confirmResolve()}>
                    {t('pages.incidentDetail.confirmTransition')}
                  </button>
                </div>
              </div>
            )}
          </div>

          <div className={styles.actionWrap}>
            <button
              type="button"
              className={styles.btnSecondary}
              disabled={!canAct}
              onClick={() => {
                setAssignMenuOpen((open) => !open);
                setStatusMenuOpen(false);
              }}
            >
              {t('pages.incidentDetail.assign')} ▾
            </button>
            {assignMenuOpen && canAct && (
              <div className={styles.menu}>
                <div className={styles.menuLabel}>{t('pages.incidentDetail.onCallAndOwners')}</div>
                {incident.assignee && (
                  <button type="button" className={styles.personRow} onClick={() => void pickAssignee(null)}>
                    <span>{t('pages.incidentDetail.unassign')}</span>
                  </button>
                )}
                {assignableUsers.map((person) => (
                  <button
                    key={person.id}
                    type="button"
                    className={
                      incident.assignee?.id === person.id ? `${styles.personRow} ${styles.personRowActive}` : styles.personRow
                    }
                    onClick={() => void pickAssignee(person.id)}
                  >
                    <span className={styles.personAvatar}>{initialsFor(person.name)}</span>
                    <span>{person.name}</span>
                  </button>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>

      {authStore.role === 'VIEWER' && (
        <div className={styles.banner}>
          <span className={styles.dot} />
          {t('pages.incidentDetail.viewerBanner')}
        </div>
      )}

      {store.mutationError && (
        <div className={styles.mutationError} role="alert">
          {t(store.mutationError)}
        </div>
      )}

      <div className={styles.body}>
        <div className={styles.column}>
          <div className={styles.card}>
            <div className={styles.cardTitle}>{t('pages.incidentDetail.description')}</div>
            <p className={styles.description}>{incident.description || t('pages.incidentDetail.noDescription')}</p>
          </div>

          <div className={styles.card}>
            <div className={styles.cardTitle}>{t('pages.incidentDetail.linkedService')}</div>
            <div className={styles.serviceHeader}>
              <span className={styles.serviceIcon} aria-hidden="true">
                {incident.service.name.slice(0, 2).toLowerCase()}
              </span>
              <div>
                <div className={styles.serviceName}>{incident.service.name}</div>
                {linkedService?.team && (
                  <div className={styles.serviceMeta}>{t('pages.incidentDetail.owner', { name: linkedService.owner.name })}</div>
                )}
              </div>
              {linkedService && (
                <div className={styles.serviceStats}>
                  {linkedService.team && (
                    <div>
                      <div className={styles.statLabel}>{t('pages.incidentDetail.team')}</div>
                      <div className={styles.statValue}>{linkedService.team}</div>
                    </div>
                  )}
                  {linkedService.availabilityTarget && (
                    <div>
                      <div className={styles.statLabel}>{t('pages.incidentDetail.availabilityTarget')}</div>
                      <div className={styles.statValue}>{linkedService.availabilityTarget}%</div>
                    </div>
                  )}
                  {linkedService.escalationContact && (
                    <div>
                      <div className={styles.statLabel}>{t('pages.incidentDetail.escalationContact')}</div>
                      <div className={styles.statValue}>{linkedService.escalationContact}</div>
                    </div>
                  )}
                </div>
              )}
            </div>
          </div>
        </div>

        <div className={styles.timelineCard}>
          <div className={styles.timelineHeader}>
            <span className={styles.cardTitle} style={{ margin: 0 }}>
              {t('pages.incidentDetail.activityTimeline')}
            </span>
            <span className={styles.timelineKicker}>{t('pages.incidentDetail.auditTrail')}</span>
          </div>
          <div className={styles.timelineBody}>
            {store.activityStatus === 'loading' && <div role="status">{t('common.loading')}</div>}
            {store.activityStatus === 'loaded' && store.activity.length === 0 && (
              <div>{t('pages.incidentDetail.noActivity')}</div>
            )}
            {store.activity
              .slice()
              .reverse()
              .map((entry, index, all) => (
                <div className={styles.event} key={entry.id}>
                  <div className={styles.eventRail}>
                    <span
                      className={`${styles.eventDot} ${badgeStyles[`dot-${activityEventKind(entry)}`]}`}
                    />
                    {index < all.length - 1 && <span className={styles.eventLine} />}
                  </div>
                  <div className={styles.eventBody}>
                    <div className={styles.eventText}>{formatActivityText(entry, t)}</div>
                    <div className={styles.eventMeta}>
                      <span className={styles.mono} title={entry.createdAt}>
                        {formatRelativeTime(entry.createdAt, now, i18n.language)}
                      </span>
                    </div>
                    {entry.comment && <div className={styles.eventComment}>{entry.comment}</div>}
                  </div>
                </div>
              ))}
          </div>
        </div>
      </div>
    </div>
  );
});
