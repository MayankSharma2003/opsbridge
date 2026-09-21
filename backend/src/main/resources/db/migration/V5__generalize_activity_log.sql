-- Generalizes incident_activity into the single append-only audit log for workflow mutations on
-- both incidents and services (project rule: "every workflow mutation ... appends a row to
-- incident_activity"). Service mutations have no incident, so incident_id becomes nullable and a
-- parallel service_id is added; entity_type says which one a row is about. Keeping one table means
-- the dashboard's recent-activity feed stays a single ordered query instead of a UNION.
--
-- Additive only: V1 is untouched, and existing rows backfill to entity_type = 'INCIDENT'.

ALTER TABLE incident_activity ALTER COLUMN incident_id DROP NOT NULL;

ALTER TABLE incident_activity ADD COLUMN service_id UUID REFERENCES services (id);

ALTER TABLE incident_activity
    ADD COLUMN entity_type VARCHAR(20) NOT NULL DEFAULT 'INCIDENT'
        CHECK (entity_type IN ('INCIDENT', 'SERVICE'));

-- Which attribute a row is about, for events that can touch one of several fields (a service edit
-- may change name, owner and team in one request). One row per changed field keeps from_value and
-- to_value meaningful scalars rather than a serialized blob in comment.
ALTER TABLE incident_activity ADD COLUMN field_name VARCHAR(64);

-- Exactly one target, and it must agree with entity_type -- so a row can never be orphaned or
-- claim to be about an incident while pointing at a service.
ALTER TABLE incident_activity ADD CONSTRAINT incident_activity_entity_target_check CHECK (
    (entity_type = 'INCIDENT' AND incident_id IS NOT NULL AND service_id IS NULL)
    OR (entity_type = 'SERVICE' AND service_id IS NOT NULL AND incident_id IS NULL)
);

-- Widening an enum-like constraint is a drop/add in a new migration, exactly as the V1 header
-- describes. V1 declared this CHECK inline, so its name was generated rather than chosen; the
-- constraint is looked up by the column it guards instead of assuming that generated name, so this
-- migration cannot half-apply against a database where the name differs.
DO $$
DECLARE
    constraint_name TEXT;
BEGIN
    SELECT con.conname INTO constraint_name
    FROM pg_constraint con
    JOIN pg_attribute att
        ON att.attrelid = con.conrelid AND att.attnum = ANY (con.conkey)
    WHERE con.conrelid = 'incident_activity'::regclass
      AND con.contype = 'c'
      AND att.attname = 'event_type';

    IF constraint_name IS NULL THEN
        RAISE EXCEPTION 'No CHECK constraint found on incident_activity.event_type to widen';
    END IF;

    EXECUTE format('ALTER TABLE incident_activity DROP CONSTRAINT %I', constraint_name);
END $$;

ALTER TABLE incident_activity ADD CONSTRAINT incident_activity_event_type_check CHECK (event_type IN (
    'CREATED', 'ASSIGNED', 'REASSIGNED', 'TRIAGED', 'STATUS_CHANGED',
    'ESCALATED', 'PRIORITY_CHANGED', 'SEVERITY_CHANGED', 'SERVICE_LINKED',
    'RESOLUTION_NOTE_ADDED', 'SLA_BREACHED',
    'TITLE_CHANGED', 'DESCRIPTION_CHANGED',
    'SERVICE_CREATED', 'SERVICE_UPDATED', 'SERVICE_ARCHIVED'
));

CREATE INDEX idx_incident_activity_service_id ON incident_activity (service_id);
CREATE INDEX idx_incident_activity_entity_type ON incident_activity (entity_type);
