import { useEffect } from 'react';
import { observer } from 'mobx-react-lite';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { useDashboardStore } from '../hooks/useStores';
import { PageHeader } from '../components/PageHeader';
import { AsyncState } from '../components/AsyncState';
import { StatusBadge } from '../components/StatusBadge';
import { PriorityBadge } from '../components/PriorityBadge';
import { formatRelativeTime, formatWeekday } from '../utils/time';
import { formatMinutes } from '../utils/formatMinutes';
import type { MttrTrendPoint } from '../types/dashboard';
import styles from './DashboardPage.module.css';

const CHART_WIDTH = 280;
const CHART_HEIGHT = 100;

// Splits the trend into runs of non-null points -- a null (no resolutions that day) breaks the line instead of interpolating through it as zero.
function buildSegments(trend: MttrTrendPoint[]): { x: number; y: number }[][] {
  const values = trend.map((point) => point.mttrMinutes).filter((value): value is number => value !== null);
  const maxValue = values.length > 0 ? Math.max(...values) : 0;
  const stepX = trend.length > 1 ? CHART_WIDTH / (trend.length - 1) : 0;

  const segments: { x: number; y: number }[][] = [];
  let current: { x: number; y: number }[] = [];
  trend.forEach((point, index) => {
    if (point.mttrMinutes === null) {
      if (current.length > 0) segments.push(current);
      current = [];
      return;
    }
    const y = maxValue === 0 ? CHART_HEIGHT : CHART_HEIGHT - (point.mttrMinutes / maxValue) * CHART_HEIGHT;
    current.push({ x: index * stepX, y });
  });
  if (current.length > 0) segments.push(current);
  return segments;
}

export const DashboardPage = observer(function DashboardPage() {
  const store = useDashboardStore();
  const navigate = useNavigate();
  const { t, i18n } = useTranslation();

  useEffect(() => {
    void store.fetchSummary();
    void store.fetchRecentIncidents();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const summary = store.summary;
  const hasTrendData = summary ? summary.mttrTrend.some((point) => point.mttrMinutes !== null) : false;
  const maxPriorityCount = summary ? Math.max(1, ...summary.priorityDistribution.map((entry) => entry.count)) : 1;
  // Percentages are of the open total, not the bar's own scale -- bar length is relative to the biggest priority, but the % must reflect share of all open incidents.
  const totalOpen = summary ? summary.priorityDistribution.reduce((sum, entry) => sum + entry.count, 0) : 0;

  return (
    <>
      <PageHeader
        title={t('pages.dashboard.title')}
        description={
          store.status === 'loaded' && store.lastRefreshedAt ? (
            <span className={styles.refreshRow}>
              {t('pages.dashboard.refreshedAt', {
                time: formatRelativeTime(store.lastRefreshedAt.toISOString(), new Date(), i18n.language),
              })}
              <button type="button" className={styles.refreshButton} onClick={() => void store.fetchSummary()}>
                {t('common.refresh')}
              </button>
            </span>
          ) : undefined
        }
      />

      {store.status === 'loading' && <AsyncState role="status">{t('common.loading')}</AsyncState>}

      {store.status === 'error' && (
        <AsyncState
          role="alert"
          title={t('pages.dashboard.errorTitle')}
          description={store.error && t(store.error)}
          action={
            <button type="button" className={styles.refreshButton} onClick={() => void store.fetchSummary()}>
              {t('common.retry')}
            </button>
          }
        />
      )}

      {store.isEmpty && (
        <AsyncState title={t('pages.dashboard.emptyTitle')} description={t('pages.dashboard.emptyDescription')} />
      )}

      {store.status === 'loaded' && summary && !store.isEmpty && (
        <>
          <div className={styles.kpiRow}>
            <div className={styles.kpiCard}>
              <div className={styles.kpiLabel}>{t('pages.dashboard.openIncidents')}</div>
              <div className={styles.kpiValue}>{summary.kpis.openIncidents}</div>
              {summary.kpis.openIncidents > 0 && (
                <div className={styles.kpiSubLine}>
                  {t('pages.dashboard.unassigned', { count: summary.kpis.unassignedIncidents })}
                </div>
              )}
              {summary.kpis.openIncidents > 0 && (
                <div className={styles.kpiBreakdown}>
                  {summary.priorityDistribution
                    .filter((entry) => entry.count > 0)
                    .map((entry) => (
                      <div
                        key={entry.priority}
                        className={`${styles.kpiBreakdownSegment} ${styles[`bar-${entry.priority}`]}`}
                        style={{ width: `${(entry.count / summary.kpis.openIncidents) * 100}%` }}
                      />
                    ))}
                </div>
              )}
            </div>
            <div className={styles.kpiCard}>
              <div className={styles.kpiLabel}>{t('pages.dashboard.slaBreaches')}</div>
              <div className={styles.kpiValue}>{summary.kpis.slaBreachesThisWeek}</div>
            </div>
            <div className={styles.kpiCard}>
              <div className={styles.kpiLabel}>{t('pages.dashboard.mttr')}</div>
              <div className={styles.kpiValue}>
                {summary.kpis.mttrMinutes === null ? (
                  <span className={styles.kpiEmpty}>{t('pages.dashboard.noMttrData')}</span>
                ) : (
                  formatMinutes(summary.kpis.mttrMinutes, t)
                )}
              </div>
            </div>
          </div>

          <div className={styles.chartRow}>
            <div className={styles.card}>
              <div className={styles.cardTitle}>{t('pages.dashboard.priorityDistribution')}</div>
              <div className={styles.priorityList}>
                {summary.priorityDistribution.map((entry) => (
                  <div key={entry.priority} className={styles.priorityRow}>
                    <div className={styles.priorityLabel}>
                      {t(`priority.${entry.priority}`)}
                      <span className={styles.priorityDescriptor}>{t(`priorityDescriptor.${entry.priority}`)}</span>
                    </div>
                    <div className={styles.priorityBarTrack}>
                      <div
                        className={`${styles.priorityBarFill} ${styles[`bar-${entry.priority}`]}`}
                        style={{ width: `${(entry.count / maxPriorityCount) * 100}%` }}
                      />
                    </div>
                    <div className={styles.priorityCount}>
                      {totalOpen > 0
                        ? t('pages.dashboard.openCountWithPercent', {
                            count: entry.count,
                            percent: Math.round((entry.count / totalOpen) * 100),
                          })
                        : t('pages.dashboard.openCount', { count: entry.count })}
                    </div>
                  </div>
                ))}
              </div>
            </div>

            <div className={styles.card}>
              <div className={styles.cardTitle}>{t('pages.dashboard.mttrTrendTitle')}</div>
              <div className={styles.cardSubtitle}>{t('pages.dashboard.mttrTrendSubtitle')}</div>
              {hasTrendData ? (
                <>
                  <svg
                    className={styles.trendSvg}
                    viewBox={`0 0 ${CHART_WIDTH} ${CHART_HEIGHT}`}
                    role="img"
                    aria-label={t('pages.dashboard.mttrLegend')}
                  >
                    {buildSegments(summary.mttrTrend).map((segment, index) => (
                      <g key={index}>
                        <polyline
                          className={styles.trendLine}
                          points={segment.map((p) => `${p.x},${p.y}`).join(' ')}
                        />
                        {segment.map((p, pointIndex) => (
                          <circle key={pointIndex} className={styles.trendDot} cx={p.x} cy={p.y} r={2.5} />
                        ))}
                      </g>
                    ))}
                  </svg>
                  <div className={styles.trendLabels}>
                    {summary.mttrTrend.map((point) => (
                      <span key={point.date}>{formatWeekday(point.date, i18n.language)}</span>
                    ))}
                  </div>
                </>
              ) : (
                <div className={styles.trendEmpty}>{t('pages.dashboard.noMttrData')}</div>
              )}
            </div>
          </div>

          <div className={`${styles.card} ${styles.recentCard}`}>
            <div className={styles.recentHeader}>
              <span className={styles.cardTitle}>{t('pages.dashboard.recentIncidents')}</span>
              <button type="button" className={styles.viewAll} onClick={() => navigate('/incidents')}>
                {t('pages.dashboard.viewAll')}
              </button>
            </div>

            {store.recentStatus === 'loading' && <div role="status">{t('common.loading')}</div>}
            {store.recentStatus === 'error' && (
              <div role="alert" className={styles.recentEmpty}>
                {t('pages.dashboard.recentIncidentsError')}
              </div>
            )}
            {store.recentStatus === 'loaded' && store.recentIncidents.length === 0 && (
              <div className={styles.recentEmpty}>{t('pages.dashboard.noRecentIncidents')}</div>
            )}

            {store.recentIncidents.length > 0 && (
              <table className={styles.recentTable}>
                <thead>
                  <tr>
                    <th>{t('pages.incidents.columnId')}</th>
                    <th>{t('pages.incidents.columnTitle')}</th>
                    <th>{t('pages.incidents.columnService')}</th>
                    <th>{t('pages.incidents.columnPriority')}</th>
                    <th>{t('pages.incidents.columnStatus')}</th>
                  </tr>
                </thead>
                <tbody>
                  {store.recentIncidents.map((incident) => (
                    <tr
                      key={incident.id}
                      className={styles.recentRow}
                      onClick={() => navigate(`/incidents/${incident.id}`)}
                    >
                      <td className={styles.recentMono}>{incident.publicKey}</td>
                      <td className={styles.recentTitle}>{incident.title}</td>
                      <td className={styles.recentMono}>{incident.service.name}</td>
                      <td>
                        <PriorityBadge priority={incident.priority} />
                      </td>
                      <td>
                        <StatusBadge status={incident.status} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </>
      )}
    </>
  );
});
