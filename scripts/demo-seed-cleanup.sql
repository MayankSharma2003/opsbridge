-- ============================================================================================
-- OpsBridge demo data cleanup
--
-- Removes exactly what scripts/demo-seed.sql adds -- nothing else. Not a Flyway migration; run it
-- by hand whenever you want to reset back to a clean dev database:
--
--   psql "$DB_URL" -f scripts/demo-seed-cleanup.sql
--   # or, using the values from .env:
--   PGPASSWORD="$DB_PASSWORD" psql -h localhost -U "$DB_USERNAME" -d opsbridge -f scripts/demo-seed-cleanup.sql
--
-- Safe to run even if the seed script was never run (or was already cleaned up) -- every DELETE
-- below is scoped to the same fixed set of demo service names the seed script uses, so it simply
-- deletes zero rows in that case. It never touches the Flyway-seeded users, SLA policies, or any
-- service/incident you created yourself.
-- ============================================================================================

\set ON_ERROR_STOP on

BEGIN;

-- The 9 service names scripts/demo-seed.sql creates. Everything this script removes is scoped to
-- incidents/activity attached to these services, plus the services themselves.
CREATE TEMP TABLE tmp_demo_service_names (name VARCHAR(255)) ON COMMIT DROP;
INSERT INTO tmp_demo_service_names (name) VALUES
    ('payments-api'), ('checkout-web'), ('auth-service'), ('notifications-worker'),
    ('search-api'), ('billing-gateway'), ('customer-portal'),
    ('legacy-inventory-sync'), ('sms-gateway-v1');

-- 1. Activity rows for demo incidents.
DELETE FROM incident_activity
WHERE incident_id IN (
    SELECT id FROM incidents
    WHERE service_id IN (SELECT id FROM services WHERE name IN (SELECT name FROM tmp_demo_service_names))
);

-- 2. Demo incidents themselves.
DELETE FROM incidents
WHERE service_id IN (SELECT id FROM services WHERE name IN (SELECT name FROM tmp_demo_service_names));

-- 3. Demo services.
DELETE FROM services
WHERE name IN (SELECT name FROM tmp_demo_service_names);

COMMIT;

-- --------------------------------------------------------------------------------------------
-- Summary -- report what's left referencing the demo service names (should always be 0/0/0 after
-- a clean run; a nonzero count would mean something else now depends on a same-named service).
-- --------------------------------------------------------------------------------------------
SELECT
    (SELECT count(*) FROM services WHERE name IN (
        'payments-api', 'checkout-web', 'auth-service', 'notifications-worker',
        'search-api', 'billing-gateway', 'customer-portal',
        'legacy-inventory-sync', 'sms-gateway-v1'
    )) AS services_remaining;
