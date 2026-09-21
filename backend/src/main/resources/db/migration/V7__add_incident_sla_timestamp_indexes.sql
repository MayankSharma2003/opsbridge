-- V1 indexes status/priority/sla_state but not the two timestamp columns Chunk 7's dashboard
-- aggregates filter on directly: the SLA-breach candidate query (sla_breached_at) and the MTTR /
-- MTTR-trend queries (sla_resolved_at). Additive only, V1 untouched.

CREATE INDEX idx_incidents_sla_breached_at ON incidents (sla_breached_at);
CREATE INDEX idx_incidents_sla_resolved_at ON incidents (sla_resolved_at);
