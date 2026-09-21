-- Core OpsBridge schema: users, services, incidents, incident_activity, sla_policies.
-- Enum-like values are VARCHAR + CHECK rather than native Postgres ENUM types, so adding a
-- new value later is a plain ALTER TABLE ... DROP/ADD CONSTRAINT in a new migration.

CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    external_subject VARCHAR(255) UNIQUE,
    name            VARCHAR(255) NOT NULL,
    email           VARCHAR(255) NOT NULL UNIQUE,
    password_hash   VARCHAR(255),
    role            VARCHAR(20) NOT NULL CHECK (role IN ('VIEWER', 'OPERATOR', 'ADMIN')),
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE services (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                 VARCHAR(255) NOT NULL,
    description          TEXT,
    owner_id             UUID NOT NULL REFERENCES users (id),
    team                 VARCHAR(255),
    escalation_contact   VARCHAR(255),
    availability_target  NUMERIC(5, 2),
    status               VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_services_status ON services (status);

CREATE TABLE sla_policies (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    priority                    VARCHAR(5) NOT NULL CHECK (priority IN ('P1', 'P2', 'P3', 'P4')),
    severity                    VARCHAR(5) CHECK (severity IN ('SEV1', 'SEV2', 'SEV3', 'SEV4')),
    response_target_minutes     INTEGER NOT NULL,
    mitigation_target_minutes   INTEGER NOT NULL,
    resolution_target_minutes   INTEGER NOT NULL,
    active                      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- severity = NULL means "applies to any severity for this priority" (see V3 seed data).
-- Plain UNIQUE(priority, severity) would not stop two NULL-severity rows for the same
-- priority, since SQL NULLs never compare equal, so uniqueness is enforced with two
-- partial indexes instead.
CREATE UNIQUE INDEX uq_sla_policies_priority_any_severity ON sla_policies (priority) WHERE severity IS NULL;
CREATE UNIQUE INDEX uq_sla_policies_priority_severity ON sla_policies (priority, severity) WHERE severity IS NOT NULL;

CREATE SEQUENCE incident_public_key_seq START WITH 1000;

CREATE TABLE incidents (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    public_key                  VARCHAR(20) NOT NULL UNIQUE,
    title                       VARCHAR(255) NOT NULL,
    description                 TEXT,
    service_id                  UUID NOT NULL REFERENCES services (id),
    reporter_id                 UUID NOT NULL REFERENCES users (id),
    assignee_id                 UUID REFERENCES users (id),
    priority                    VARCHAR(5) NOT NULL CHECK (priority IN ('P1', 'P2', 'P3', 'P4')),
    severity                    VARCHAR(5) NOT NULL CHECK (severity IN ('SEV1', 'SEV2', 'SEV3', 'SEV4')),
    status                      VARCHAR(20) NOT NULL DEFAULT 'OPEN'
                                 CHECK (status IN ('OPEN', 'TRIAGED', 'INVESTIGATING', 'MITIGATED', 'RESOLVED', 'CANCELLED')),
    sla_policy_id                UUID REFERENCES sla_policies (id),
    sla_response_target_at       TIMESTAMPTZ,
    sla_mitigation_target_at     TIMESTAMPTZ,
    sla_resolution_target_at     TIMESTAMPTZ,
    sla_responded_at             TIMESTAMPTZ,
    sla_mitigated_at             TIMESTAMPTZ,
    sla_resolved_at              TIMESTAMPTZ,
    sla_breached_at              TIMESTAMPTZ,
    sla_state                    VARCHAR(20) NOT NULL DEFAULT 'ON_TRACK'
                                  CHECK (sla_state IN ('ON_TRACK', 'AT_RISK', 'BREACHED', 'MET')),
    resolution_notes              TEXT,
    version                       INTEGER NOT NULL DEFAULT 0,
    created_at                    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_incidents_status ON incidents (status);
CREATE INDEX idx_incidents_priority ON incidents (priority);
CREATE INDEX idx_incidents_severity ON incidents (severity);
CREATE INDEX idx_incidents_service_id ON incidents (service_id);
CREATE INDEX idx_incidents_assignee_id ON incidents (assignee_id);
CREATE INDEX idx_incidents_created_at ON incidents (created_at);
CREATE INDEX idx_incidents_sla_state ON incidents (sla_state);

CREATE TABLE incident_activity (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id  UUID NOT NULL REFERENCES incidents (id),
    actor_id     UUID NOT NULL REFERENCES users (id),
    event_type   VARCHAR(50) NOT NULL CHECK (event_type IN (
                     'CREATED', 'ASSIGNED', 'REASSIGNED', 'TRIAGED', 'STATUS_CHANGED',
                     'ESCALATED', 'PRIORITY_CHANGED', 'SEVERITY_CHANGED', 'SERVICE_LINKED',
                     'RESOLUTION_NOTE_ADDED', 'SLA_BREACHED'
                 )),
    from_value   VARCHAR(255),
    to_value     VARCHAR(255),
    comment      TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_incident_activity_incident_id ON incident_activity (incident_id);
CREATE INDEX idx_incident_activity_created_at ON incident_activity (created_at);
