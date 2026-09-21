-- ============================================================================================
-- OpsBridge demo data seed
--
-- Standalone SQL script for populating a *local dev* database with a richer, realistic set of
-- incidents/services for manual testing (filtering, pagination, dashboard charts). This is NOT a
-- Flyway migration -- it lives outside backend/src/main/resources/db/migration and is never run
-- by the application. Run it by hand whenever you want demo data:
--
--   psql "$DB_URL" -f scripts/demo-seed.sql
--   # or, using the values from .env:
--   PGPASSWORD="$DB_PASSWORD" psql -h localhost -U "$DB_USERNAME" -d opsbridge -f scripts/demo-seed.sql
--
-- Companion cleanup script (removes exactly what this script adds, nothing else):
--   scripts/demo-seed-cleanup.sql
--
-- Requires the Flyway dev-user seed (V2__seed_dev_users.sql) to already be applied, since demo
-- incidents/services are attached to the real operator@opsbridge.local / admin@opsbridge.local
-- users rather than inventing new ones. Idempotency is enforced below: this script refuses to run
-- a second time on top of itself.
-- ============================================================================================

-- Abort the whole script on the first error (e.g. the idempotency guard below) instead of psql
-- continuing to run every remaining statement against an already-aborted transaction.
\set ON_ERROR_STOP on

BEGIN;

-- --------------------------------------------------------------------------------------------
-- Guards
-- --------------------------------------------------------------------------------------------

DO $$
BEGIN
    IF (SELECT count(*) FROM users WHERE email IN ('operator@opsbridge.local', 'admin@opsbridge.local')) < 2 THEN
        RAISE EXCEPTION 'Expected dev users (operator@opsbridge.local, admin@opsbridge.local) not found -- run Flyway migrations first.';
    END IF;

    IF EXISTS (
        SELECT 1 FROM services WHERE name IN (
            'payments-api', 'checkout-web', 'auth-service', 'notifications-worker',
            'search-api', 'billing-gateway', 'customer-portal',
            'legacy-inventory-sync', 'sms-gateway-v1'
        )
    ) THEN
        RAISE EXCEPTION 'Demo seed data already present -- run scripts/demo-seed-cleanup.sql first, then re-run this script.';
    END IF;
END $$;

-- --------------------------------------------------------------------------------------------
-- 1. Services (7 ACTIVE + 2 ARCHIVED, owned by the seeded operator/admin dev users)
-- --------------------------------------------------------------------------------------------

INSERT INTO services (name, description, owner_id, team, escalation_contact, availability_target, status)
SELECT
    v.name,
    v.description,
    (SELECT id FROM users WHERE email = v.owner_email),
    v.team,
    v.escalation_contact,
    v.availability_target,
    v.status
FROM (VALUES
    ('payments-api',          'Card and bank-transfer payment processing API.',       'operator@opsbridge.local', 'Payments',          'payments-oncall@opsbridge.local',  99.95, 'ACTIVE'),
    ('checkout-web',          'Customer-facing checkout and cart web application.',   'admin@opsbridge.local',    'Commerce Frontend', 'checkout-oncall@opsbridge.local',  99.90, 'ACTIVE'),
    ('auth-service',          'Authentication, sessions, and token issuance.',        'operator@opsbridge.local', 'Identity',          'identity-oncall@opsbridge.local',  99.99, 'ACTIVE'),
    ('notifications-worker',  'Async email/SMS/push notification delivery.',          'admin@opsbridge.local',    'Platform',          'platform-oncall@opsbridge.local',  99.50, 'ACTIVE'),
    ('search-api',            'Product and content search indexing/query API.',       'operator@opsbridge.local', 'Discovery',         'discovery-oncall@opsbridge.local', 99.90, 'ACTIVE'),
    ('billing-gateway',       'Subscription billing and invoicing gateway.',          'admin@opsbridge.local',    'Payments',          'payments-oncall@opsbridge.local',  99.95, 'ACTIVE'),
    ('customer-portal',       'Self-service account and support portal.',             'operator@opsbridge.local', 'Commerce Frontend', 'checkout-oncall@opsbridge.local',  99.50, 'ACTIVE'),
    ('legacy-inventory-sync', 'Deprecated warehouse inventory sync job.',             'admin@opsbridge.local',    'Platform',          'platform-oncall@opsbridge.local',  95.00, 'ARCHIVED'),
    ('sms-gateway-v1',        'Retired first-generation SMS provider integration.',   'operator@opsbridge.local', 'Platform',          'platform-oncall@opsbridge.local',  95.00, 'ARCHIVED')
) AS v(name, description, owner_email, team, escalation_contact, availability_target, status);

-- --------------------------------------------------------------------------------------------
-- 2. Incidents -- 48 rows: 6 statuses x 4 priorities x 2 rows, severities rotated SEV1..SEV4.
--    Timestamps are computed relative to now() so the script stays valid whenever it's run.
--    Every (responded/mitigated/resolved)_after_min is minutes after sla_started_at, chosen
--    against each row's *real* SLA policy target minutes so the live SlaCalculator produces a
--    realistic, varied mix of ON_TRACK / AT_RISK / BREACHED / MET when the app reads these rows
--    (see IncidentService.refreshSlaState -- state is recomputed on every read except for
--    CANCELLED, whose evaluate() short-circuits to the persisted column, so cancelled rows carry
--    an explicit frozen_state/frozen_breached_after_min below instead of being left to chance).
-- --------------------------------------------------------------------------------------------

CREATE TEMP TABLE tmp_incident_seed (
    seq                       INTEGER PRIMARY KEY,
    service_name              VARCHAR(255) NOT NULL,
    status                    VARCHAR(20) NOT NULL,
    priority                  VARCHAR(5) NOT NULL,
    severity                  VARCHAR(5) NOT NULL,
    title                     VARCHAR(255) NOT NULL,
    reporter_email            VARCHAR(255) NOT NULL,
    assignee_email            VARCHAR(255),
    created_ago               INTERVAL NOT NULL,
    -- Integer minutes (not NUMERIC): every offset below is a whole number, and make_interval()'s
    -- mins parameter is typed int, so keeping these columns int avoids a cast at every call site.
    assigned_after_min        INTEGER,
    responded_after_min       INTEGER,
    mitigated_after_min       INTEGER,
    resolved_after_min        INTEGER,
    cancelled_after_min       INTEGER,
    resolution_notes          TEXT,
    frozen_sla_state          VARCHAR(20),
    frozen_breached_after_min INTEGER
) ON COMMIT DROP;

INSERT INTO tmp_incident_seed
    (seq, service_name, status, priority, severity, title, reporter_email, assignee_email,
     created_ago, assigned_after_min, responded_after_min, mitigated_after_min, resolved_after_min,
     cancelled_after_min, resolution_notes, frozen_sla_state, frozen_breached_after_min)
VALUES
    -- OPEN (seq 1-8): mostly unassigned, nobody has responded yet. created_ago is tuned against
    -- each row's response-target minutes to land on a mix of ON_TRACK / AT_RISK / BREACHED.
    (1,  'payments-api',         'OPEN', 'P1', 'SEV1', 'Elevated error rate on checkout requests',           'operator@opsbridge.local', NULL,                       INTERVAL '2 minutes',   NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL),
    (2,  'checkout-web',         'OPEN', 'P1', 'SEV2', 'Increased p99 latency under load',                   'admin@opsbridge.local',    'operator@opsbridge.local', INTERVAL '4.5 minutes', 2,    NULL, NULL, NULL, NULL, NULL, NULL, NULL),
    (3,  'auth-service',         'OPEN', 'P2', 'SEV3', 'Intermittent 502s from upstream dependency',         'operator@opsbridge.local', NULL,                       INTERVAL '20 minutes',  NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL),
    (4,  'notifications-worker', 'OPEN', 'P2', 'SEV4', 'Database connection pool exhaustion',                'admin@opsbridge.local',    NULL,                       INTERVAL '5 hours',     NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL),
    (5,  'search-api',           'OPEN', 'P3', 'SEV1', 'Failed background job retries spiking',              'operator@opsbridge.local', NULL,                       INTERVAL '3 days',      NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL),
    (6,  'billing-gateway',      'OPEN', 'P3', 'SEV2', 'Authentication tokens rejected for valid sessions',  'admin@opsbridge.local',    'operator@opsbridge.local', INTERVAL '50 minutes',  5,    NULL, NULL, NULL, NULL, NULL, NULL, NULL),
    (7,  'customer-portal',      'OPEN', 'P4', 'SEV3', 'Partial outage in EU region',                        'operator@opsbridge.local', NULL,                       INTERVAL '2 hours',     NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL),
    (8,  'payments-api',         'OPEN', 'P4', 'SEV4', 'Memory leak causing pod restarts',                   'admin@opsbridge.local',    NULL,                       INTERVAL '10 days',     NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL),

    -- TRIAGED (seq 9-16): responded, next unmet target is mitigation.
    (9,  'checkout-web',         'TRIAGED', 'P1', 'SEV1', 'Rate limiter falsely blocking legitimate traffic', 'operator@opsbridge.local', 'admin@opsbridge.local',    INTERVAL '13 minutes',  1,  2,   NULL, NULL, NULL, NULL, NULL, NULL),
    (10, 'auth-service',         'TRIAGED', 'P1', 'SEV2', 'Stale cache serving outdated data',                 'admin@opsbridge.local',    'operator@opsbridge.local', INTERVAL '45 minutes',  2,  4,   NULL, NULL, NULL, NULL, NULL, NULL),
    (11, 'notifications-worker', 'TRIAGED', 'P2', 'SEV3', 'Third-party webhook delivery failures',             'operator@opsbridge.local', 'admin@opsbridge.local',    INTERVAL '3 hours',     5,  10,  NULL, NULL, NULL, NULL, NULL, NULL),
    (12, 'search-api',           'TRIAGED', 'P2', 'SEV4', 'Disk usage approaching capacity on primary nodes',  'admin@opsbridge.local',    'operator@opsbridge.local', INTERVAL '40 minutes',  5,  12,  NULL, NULL, NULL, NULL, NULL, NULL),
    (13, 'billing-gateway',      'TRIAGED', 'P3', 'SEV1', 'Elevated error rate on checkout requests',          'operator@opsbridge.local', 'admin@opsbridge.local',    INTERVAL '100 minutes', 10, 25,  NULL, NULL, NULL, NULL, NULL, NULL),
    (14, 'customer-portal',      'TRIAGED', 'P3', 'SEV2', 'Increased p99 latency under load',                  'admin@opsbridge.local',    'operator@opsbridge.local', INTERVAL '1 day',       15, 50,  NULL, NULL, NULL, NULL, NULL, NULL),
    (15, 'payments-api',         'TRIAGED', 'P4', 'SEV3', 'Intermittent 502s from upstream dependency',        'operator@opsbridge.local', NULL,                       INTERVAL '5 hours',     NULL, 200, NULL, NULL, NULL, NULL, NULL, NULL),
    (16, 'checkout-web',         'TRIAGED', 'P4', 'SEV4', 'Database connection pool exhaustion',               'admin@opsbridge.local',    NULL,                       INTERVAL '3 days',      NULL, 230, NULL, NULL, NULL, NULL, NULL, NULL),

    -- INVESTIGATING (seq 17-24): responded, all assigned, next unmet target is still mitigation.
    (17, 'auth-service',         'INVESTIGATING', 'P1', 'SEV1', 'Failed background job retries spiking',             'operator@opsbridge.local', 'admin@opsbridge.local',    INTERVAL '12 minutes',  1,  2,   NULL, NULL, NULL, NULL, NULL, NULL),
    (18, 'notifications-worker', 'INVESTIGATING', 'P1', 'SEV2', 'Authentication tokens rejected for valid sessions', 'admin@opsbridge.local',    'operator@opsbridge.local', INTERVAL '35 minutes',  2,  4,   NULL, NULL, NULL, NULL, NULL, NULL),
    (19, 'search-api',           'INVESTIGATING', 'P2', 'SEV3', 'Partial outage in EU region',                       'operator@opsbridge.local', 'admin@opsbridge.local',    INTERVAL '50 minutes',  5,  10,  NULL, NULL, NULL, NULL, NULL, NULL),
    (20, 'billing-gateway',      'INVESTIGATING', 'P2', 'SEV4', 'Memory leak causing pod restarts',                  'admin@opsbridge.local',    'operator@opsbridge.local', INTERVAL '2 hours',     5,  12,  NULL, NULL, NULL, NULL, NULL, NULL),
    (21, 'customer-portal',      'INVESTIGATING', 'P3', 'SEV1', 'Rate limiter falsely blocking legitimate traffic',  'operator@opsbridge.local', 'admin@opsbridge.local',    INTERVAL '100 minutes', 10, 25,  NULL, NULL, NULL, NULL, NULL, NULL),
    (22, 'payments-api',         'INVESTIGATING', 'P3', 'SEV2', 'Stale cache serving outdated data',                 'admin@opsbridge.local',    'operator@opsbridge.local', INTERVAL '8 hours',     15, 50,  NULL, NULL, NULL, NULL, NULL, NULL),
    (23, 'checkout-web',         'INVESTIGATING', 'P4', 'SEV3', 'Third-party webhook delivery failures',             'operator@opsbridge.local', 'admin@opsbridge.local',    INTERVAL '6 hours',     30, 200, NULL, NULL, NULL, NULL, NULL, NULL),
    (24, 'auth-service',         'INVESTIGATING', 'P4', 'SEV4', 'Disk usage approaching capacity on primary nodes',  'admin@opsbridge.local',    'operator@opsbridge.local', INTERVAL '2 days',      30, 230, NULL, NULL, NULL, NULL, NULL, NULL),

    -- MITIGATED (seq 25-32): responded + mitigated, all assigned, next unmet target is resolution.
    (25, 'notifications-worker', 'MITIGATED', 'P1', 'SEV1', 'Elevated error rate on checkout requests',          'operator@opsbridge.local', 'admin@opsbridge.local',    INTERVAL '100 minutes', 1,  2,   12,  NULL, NULL, NULL, NULL, NULL),
    (26, 'search-api',           'MITIGATED', 'P1', 'SEV2', 'Increased p99 latency under load',                  'admin@opsbridge.local',    'operator@opsbridge.local', INTERVAL '260 minutes', 2,  4,   25,  NULL, NULL, NULL, NULL, NULL),
    (27, 'billing-gateway',      'MITIGATED', 'P2', 'SEV3', 'Intermittent 502s from upstream dependency',        'operator@opsbridge.local', 'admin@opsbridge.local',    INTERVAL '400 minutes', 5,  10,  50,  NULL, NULL, NULL, NULL, NULL),
    (28, 'customer-portal',      'MITIGATED', 'P2', 'SEV4', 'Database connection pool exhaustion',               'admin@opsbridge.local',    'operator@opsbridge.local', INTERVAL '540 minutes', 5,  12,  55,  NULL, NULL, NULL, NULL, NULL),
    (29, 'payments-api',         'MITIGATED', 'P3', 'SEV1', 'Failed background job retries spiking',             'operator@opsbridge.local', 'admin@opsbridge.local',    INTERVAL '420 minutes', 10, 25,  110, NULL, NULL, NULL, NULL, NULL),
    (30, 'checkout-web',         'MITIGATED', 'P3', 'SEV2', 'Authentication tokens rejected for valid sessions', 'admin@opsbridge.local',    'operator@opsbridge.local', INTERVAL '1020 minutes',15, 50,  220, NULL, NULL, NULL, NULL, NULL),
    (31, 'auth-service',         'MITIGATED', 'P4', 'SEV3', 'Partial outage in EU region',                       'operator@opsbridge.local', 'admin@opsbridge.local',    INTERVAL '1800 minutes',30, 200, 450, NULL, NULL, NULL, NULL, NULL),
    (32, 'notifications-worker', 'MITIGATED', 'P4', 'SEV4', 'Memory leak causing pod restarts',                  'admin@opsbridge.local',    'operator@opsbridge.local', INTERVAL '5040 minutes',30, 230, 470, NULL, NULL, NULL, NULL, NULL),

    -- RESOLVED (seq 33-40): full lifecycle. Half MET, half BREACHED; half resolved within the
    -- last 7 days (inside the dashboard's rolling window), half well outside it.
    (33, 'search-api',           'RESOLVED', 'P1', 'SEV1', 'Rate limiter falsely blocking legitimate traffic', 'operator@opsbridge.local', 'admin@opsbridge.local',    INTERVAL '3 days',  1,  2,   12,  100,  NULL, 'Root cause identified and fix deployed; verified stable.',                NULL, NULL),
    (34, 'billing-gateway',      'RESOLVED', 'P1', 'SEV2', 'Stale cache serving outdated data',                 'admin@opsbridge.local',    'operator@opsbridge.local', INTERVAL '2 days',  2,  4,   25,  260,  NULL, 'Resolved after extended remediation; postmortem scheduled.',              NULL, NULL),
    (35, 'customer-portal',      'RESOLVED', 'P2', 'SEV3', 'Third-party webhook delivery failures',             'operator@opsbridge.local', 'admin@opsbridge.local',    INTERVAL '5 days',  5,  10,  50,  400,  NULL, 'Root cause identified and fix deployed; verified stable.',                NULL, NULL),
    (36, 'payments-api',         'RESOLVED', 'P2', 'SEV4', 'Disk usage approaching capacity on primary nodes',  'admin@opsbridge.local',    'operator@opsbridge.local', INTERVAL '6 days',  5,  12,  55,  550,  NULL, 'Resolved after extended remediation; postmortem scheduled.',              NULL, NULL),
    (37, 'checkout-web',         'RESOLVED', 'P3', 'SEV1', 'Elevated error rate on checkout requests',          'operator@opsbridge.local', 'admin@opsbridge.local',    INTERVAL '15 days', 10, 25,  110, 450,  NULL, 'Root cause identified and fix deployed; verified stable.',                NULL, NULL),
    (38, 'auth-service',         'RESOLVED', 'P3', 'SEV2', 'Increased p99 latency under load',                  'admin@opsbridge.local',    'operator@opsbridge.local', INTERVAL '20 days', 15, 50,  220, 1000, NULL, 'Resolved after extended remediation; postmortem scheduled.',              NULL, NULL),
    (39, 'notifications-worker', 'RESOLVED', 'P4', 'SEV3', 'Intermittent 502s from upstream dependency',        'operator@opsbridge.local', 'admin@opsbridge.local',    INTERVAL '35 days', 30, 200, 450, 2300, NULL, 'Root cause identified and fix deployed; verified stable.',                NULL, NULL),
    (40, 'search-api',           'RESOLVED', 'P4', 'SEV4', 'Database connection pool exhaustion',               'admin@opsbridge.local',    'operator@opsbridge.local', INTERVAL '40 days', 30, 230, 470, 2600, NULL, 'Resolved after extended remediation; postmortem scheduled.',              NULL, NULL),

    -- CANCELLED (seq 41-48): frozen sla_state/breached_at, since evaluate() never recomputes a
    -- cancelled incident. Alternates cancelled-straight-from-OPEN (never triaged, assigned) and
    -- cancelled-from-TRIAGED (responded once, left unassigned).
    (41, 'billing-gateway',      'CANCELLED', 'P1', 'SEV1', 'Failed background job retries spiking',             'operator@opsbridge.local', 'admin@opsbridge.local', INTERVAL '2 days',  1,    NULL, NULL, NULL, 10,   NULL, 'ON_TRACK', NULL),
    (42, 'customer-portal',      'CANCELLED', 'P1', 'SEV2', 'Authentication tokens rejected for valid sessions', 'admin@opsbridge.local',    NULL,                     INTERVAL '5 days',  NULL, 4,    NULL, NULL, 45,   NULL, 'ON_TRACK', NULL),
    (43, 'payments-api',         'CANCELLED', 'P2', 'SEV3', 'Partial outage in EU region',                       'operator@opsbridge.local', 'admin@opsbridge.local', INTERVAL '8 days',  5,    NULL, NULL, NULL, 20,   NULL, 'AT_RISK',  NULL),
    (44, 'checkout-web',         'CANCELLED', 'P2', 'SEV4', 'Memory leak causing pod restarts',                  'admin@opsbridge.local',    NULL,                     INTERVAL '12 days', NULL, 12,   NULL, NULL, 90,   NULL, 'AT_RISK',  NULL),
    (45, 'auth-service',         'CANCELLED', 'P3', 'SEV1', 'Rate limiter falsely blocking legitimate traffic',  'operator@opsbridge.local', 'admin@opsbridge.local', INTERVAL '15 days', 10,   NULL, NULL, NULL, 600,  NULL, 'BREACHED', 600),
    (46, 'notifications-worker', 'CANCELLED', 'P3', 'SEV2', 'Stale cache serving outdated data',                 'admin@opsbridge.local',    NULL,                     INTERVAL '18 days', NULL, 50,   NULL, NULL, 1200, NULL, 'BREACHED', 1200),
    (47, 'search-api',           'CANCELLED', 'P4', 'SEV3', 'Third-party webhook delivery failures',             'operator@opsbridge.local', 'admin@opsbridge.local', INTERVAL '22 days', 30,   NULL, NULL, NULL, 300,  NULL, 'ON_TRACK', NULL),
    (48, 'billing-gateway',      'CANCELLED', 'P4', 'SEV4', 'Disk usage approaching capacity on primary nodes',  'admin@opsbridge.local',    NULL,                     INTERVAL '28 days', NULL, 230,  NULL, NULL, 3000, NULL, 'BREACHED', 3000);

CREATE TEMP TABLE tmp_incident_ins (
    seq              INTEGER PRIMARY KEY,
    id               UUID NOT NULL,
    status           VARCHAR(20) NOT NULL,
    reporter_id      UUID NOT NULL,
    assignee_id      UUID,
    sla_started_at   TIMESTAMPTZ NOT NULL,
    assigned_at      TIMESTAMPTZ,
    responded_at     TIMESTAMPTZ,
    mitigated_at     TIMESTAMPTZ,
    resolved_at      TIMESTAMPTZ,
    cancelled_at     TIMESTAMPTZ
) ON COMMIT DROP;

WITH now_snapshot AS (
    SELECT now() AS ts
),
computed AS (
    SELECT
        s.seq,
        s.status,
        s.priority,
        s.severity,
        s.title,
        (SELECT id FROM services WHERE name = s.service_name)   AS service_id,
        (SELECT id FROM users WHERE email = s.reporter_email)   AS reporter_id,
        (SELECT id FROM users WHERE email = s.assignee_email)   AS assignee_id,
        n.ts - s.created_ago                                    AS sla_started_at,
        COALESCE(exact.id, fallback.id)                         AS sla_policy_id,
        COALESCE(exact.response_target_minutes, fallback.response_target_minutes)     AS response_target_minutes,
        COALESCE(exact.mitigation_target_minutes, fallback.mitigation_target_minutes) AS mitigation_target_minutes,
        COALESCE(exact.resolution_target_minutes, fallback.resolution_target_minutes) AS resolution_target_minutes,
        s.assigned_after_min,
        s.responded_after_min,
        s.mitigated_after_min,
        s.resolved_after_min,
        s.cancelled_after_min,
        s.resolution_notes,
        s.frozen_sla_state,
        s.frozen_breached_after_min
    FROM tmp_incident_seed s
    CROSS JOIN now_snapshot n
    LEFT JOIN sla_policies exact
           ON exact.priority = s.priority AND exact.severity = s.severity AND exact.active
    LEFT JOIN sla_policies fallback
           ON fallback.priority = s.priority AND fallback.severity IS NULL AND fallback.active
),
inserted AS (
    INSERT INTO incidents (
        public_key, title, description, service_id, reporter_id, assignee_id,
        priority, severity, status,
        sla_policy_id, sla_started_at,
        sla_response_target_at, sla_mitigation_target_at, sla_resolution_target_at,
        sla_responded_at, sla_mitigated_at, sla_resolved_at,
        sla_breached_at, sla_state,
        resolution_notes, created_at, updated_at
    )
    SELECT
        'INC-' || nextval('incident_public_key_seq'),
        c.title,
        'Demo seed incident for local testing -- ' || c.title || ' (' ||
            (SELECT name FROM services WHERE id = c.service_id) || ').',
        c.service_id, c.reporter_id, c.assignee_id,
        c.priority, c.severity, c.status,
        c.sla_policy_id, c.sla_started_at,
        c.sla_started_at + make_interval(mins => c.response_target_minutes),
        c.sla_started_at + make_interval(mins => c.mitigation_target_minutes),
        c.sla_started_at + make_interval(mins => c.resolution_target_minutes),
        CASE WHEN c.responded_after_min IS NOT NULL THEN c.sla_started_at + make_interval(mins => c.responded_after_min) END,
        CASE WHEN c.mitigated_after_min IS NOT NULL THEN c.sla_started_at + make_interval(mins => c.mitigated_after_min) END,
        CASE WHEN c.resolved_after_min  IS NOT NULL THEN c.sla_started_at + make_interval(mins => c.resolved_after_min)  END,
        CASE WHEN c.frozen_sla_state = 'BREACHED'
             THEN c.sla_started_at + make_interval(mins => c.frozen_breached_after_min) END,
        COALESCE(c.frozen_sla_state, 'ON_TRACK'),
        c.resolution_notes,
        c.sla_started_at,
        c.sla_started_at
    FROM computed c
    RETURNING id, priority, severity, status
)
-- (priority, severity, status) is a unique key across all 48 seeded rows by construction: every
-- status bucket uses the same 8 distinct (priority, severity) pairs, so the triple never repeats.
-- That makes it safe to re-derive each inserted incident's identity here, without depending on
-- RETURNING preserving the SELECT's row order (which Postgres does not guarantee).
INSERT INTO tmp_incident_ins (seq, id, status, reporter_id, assignee_id, sla_started_at, assigned_at, responded_at, mitigated_at, resolved_at, cancelled_at)
SELECT
    s.seq, i.id, s.status,
    (SELECT id FROM users WHERE email = s.reporter_email),
    (SELECT id FROM users WHERE email = s.assignee_email),
    n.ts - s.created_ago,
    CASE WHEN s.assigned_after_min  IS NOT NULL THEN (n.ts - s.created_ago) + make_interval(mins => s.assigned_after_min)  END,
    CASE WHEN s.responded_after_min IS NOT NULL THEN (n.ts - s.created_ago) + make_interval(mins => s.responded_after_min) END,
    CASE WHEN s.mitigated_after_min IS NOT NULL THEN (n.ts - s.created_ago) + make_interval(mins => s.mitigated_after_min) END,
    CASE WHEN s.resolved_after_min  IS NOT NULL THEN (n.ts - s.created_ago) + make_interval(mins => s.resolved_after_min)  END,
    CASE WHEN s.cancelled_after_min IS NOT NULL THEN (n.ts - s.created_ago) + make_interval(mins => s.cancelled_after_min) END
FROM tmp_incident_seed s
CROSS JOIN now_snapshot n
JOIN inserted i
     ON i.priority = s.priority AND i.severity = s.severity AND i.status = s.status;

-- --------------------------------------------------------------------------------------------
-- 3. Activity history -- append-only audit trail matching IncidentService's real event log, so
--    seeded incidents look like they actually lived through their workflow.
-- --------------------------------------------------------------------------------------------

-- CREATED, for every incident.
INSERT INTO incident_activity (incident_id, actor_id, event_type, from_value, to_value, entity_type, created_at)
SELECT id, reporter_id, 'CREATED', NULL, 'OPEN', 'INCIDENT', sla_started_at
FROM tmp_incident_ins;

-- ASSIGNED, wherever an assignee and an assignment offset were seeded.
INSERT INTO incident_activity (incident_id, actor_id, event_type, from_value, to_value, entity_type, created_at)
SELECT id, assignee_id, 'ASSIGNED', NULL,
       (SELECT name FROM users WHERE id = assignee_id),
       'INCIDENT', assigned_at
FROM tmp_incident_ins
WHERE assignee_id IS NOT NULL AND assigned_at IS NOT NULL;

-- STATUS_CHANGED OPEN -> TRIAGED, wherever the incident was ever responded to (TRIAGED and past).
INSERT INTO incident_activity (incident_id, actor_id, event_type, from_value, to_value, entity_type, created_at)
SELECT id, COALESCE(assignee_id, reporter_id), 'STATUS_CHANGED', 'OPEN', 'TRIAGED', 'INCIDENT', responded_at
FROM tmp_incident_ins
WHERE responded_at IS NOT NULL;

-- STATUS_CHANGED TRIAGED -> INVESTIGATING, for incidents that progressed past triage (no
-- dedicated SLA timestamp marks this step, so it's placed shortly after the response).
INSERT INTO incident_activity (incident_id, actor_id, event_type, from_value, to_value, entity_type, created_at)
SELECT id, COALESCE(assignee_id, reporter_id), 'STATUS_CHANGED', 'TRIAGED', 'INVESTIGATING', 'INCIDENT',
       responded_at + INTERVAL '5 minutes'
FROM tmp_incident_ins
WHERE status IN ('INVESTIGATING', 'MITIGATED', 'RESOLVED');

-- STATUS_CHANGED INVESTIGATING -> MITIGATED, wherever a mitigation offset was seeded.
INSERT INTO incident_activity (incident_id, actor_id, event_type, from_value, to_value, entity_type, created_at)
SELECT id, COALESCE(assignee_id, reporter_id), 'STATUS_CHANGED', 'INVESTIGATING', 'MITIGATED', 'INCIDENT', mitigated_at
FROM tmp_incident_ins
WHERE mitigated_at IS NOT NULL;

-- STATUS_CHANGED MITIGATED -> RESOLVED, plus the resolution note, for resolved incidents.
INSERT INTO incident_activity (incident_id, actor_id, event_type, from_value, to_value, entity_type, created_at)
SELECT id, COALESCE(assignee_id, reporter_id), 'STATUS_CHANGED', 'MITIGATED', 'RESOLVED', 'INCIDENT', resolved_at
FROM tmp_incident_ins
WHERE resolved_at IS NOT NULL;

INSERT INTO incident_activity (incident_id, actor_id, event_type, from_value, to_value, comment, entity_type, created_at)
SELECT id, COALESCE(assignee_id, reporter_id), 'RESOLUTION_NOTE_ADDED', NULL, NULL,
       (SELECT resolution_notes FROM tmp_incident_seed WHERE seq = tmp_incident_ins.seq),
       'INCIDENT', resolved_at
FROM tmp_incident_ins
WHERE resolved_at IS NOT NULL;

-- STATUS_CHANGED ... -> CANCELLED. Cancelled straight from OPEN when never responded to,
-- otherwise cancelled from TRIAGED.
INSERT INTO incident_activity (incident_id, actor_id, event_type, from_value, to_value, entity_type, created_at)
SELECT id, COALESCE(assignee_id, reporter_id), 'STATUS_CHANGED',
       CASE WHEN responded_at IS NOT NULL THEN 'TRIAGED' ELSE 'OPEN' END,
       'CANCELLED', 'INCIDENT', cancelled_at
FROM tmp_incident_ins
WHERE cancelled_at IS NOT NULL;

COMMIT;

-- --------------------------------------------------------------------------------------------
-- Summary
-- --------------------------------------------------------------------------------------------
SELECT
    (SELECT count(*) FROM services WHERE name IN (
        'payments-api', 'checkout-web', 'auth-service', 'notifications-worker',
        'search-api', 'billing-gateway', 'customer-portal',
        'legacy-inventory-sync', 'sms-gateway-v1'
    )) AS services_seeded,
    (SELECT count(*) FROM incidents WHERE description LIKE 'Demo seed incident for local testing%') AS incidents_seeded,
    (SELECT count(*) FROM incident_activity WHERE incident_id IN (
        SELECT id FROM incidents WHERE description LIKE 'Demo seed incident for local testing%'
    )) AS activity_rows_seeded;
