-- Adds one SEV1 override per priority, tighter than that priority's existing catch-all from V3.
-- SlaPolicyResolver already prefers an exact priority+severity match over the catch-all
-- (severity IS NULL) row; until now no exact-match row existed, so every incident resolved
-- through the catch-all regardless of severity and that preference never fired against real
-- data. These are illustrative dev defaults, not spec-mandated values -- same caveat as V3.

INSERT INTO sla_policies (priority, severity, response_target_minutes, mitigation_target_minutes, resolution_target_minutes, active) VALUES
    ('P1', 'SEV1', 3,   15,  120,  TRUE),
    ('P2', 'SEV1', 10,  30,  240,  TRUE),
    ('P3', 'SEV1', 30,  120, 480,  TRUE),
    ('P4', 'SEV1', 120, 240, 1200, TRUE);
