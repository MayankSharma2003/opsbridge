-- Default SLA policy per priority (spec section 4.2), applying to any severity.
-- P3/P4 resolution targets are given in "business days" in the spec; converted here using an
-- assumed 8-hour business day. Real business-calendar SLA math is implemented in a later chunk
-- (incident domain core) -- these are just the seeded target minutes.

INSERT INTO sla_policies (priority, severity, response_target_minutes, mitigation_target_minutes, resolution_target_minutes, active) VALUES
    ('P1', NULL, 5,   30,  240,  TRUE),   -- 4 hours
    ('P2', NULL, 15,  60,  480,  TRUE),   -- 8 hours
    ('P3', NULL, 60,  240, 960,  TRUE),   -- 2 business days (8h/day)
    ('P4', NULL, 240, 480, 2400, TRUE);   -- 5 business days (8h/day)
