-- Explicit SLA start time (spec 4.2 requires persisting it). created_at is set by the JPA
-- @PrePersist callback via Instant.now(), which the service's injected Clock cannot control;
-- sla_started_at is set by the service from that Clock, so SLA targets and the start they are
-- measured from always come from the same time source and stay deterministic in tests.

ALTER TABLE incidents ADD COLUMN sla_started_at TIMESTAMPTZ;

-- Existing rows (dev/local only at this point) fall back to their creation time.
UPDATE incidents SET sla_started_at = created_at WHERE sla_started_at IS NULL;
