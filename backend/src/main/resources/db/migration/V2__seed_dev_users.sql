-- Clearly-labeled development seed data (not real users). Passwords are for local/dev login
-- only: viewer -> Viewer#Dev2026, operator -> Operator#Dev2026, admin -> Admin#Dev2026.
-- Hashes generated with the same BCryptPasswordEncoder (strength 10) the app uses at runtime.

INSERT INTO users (name, email, password_hash, role, active) VALUES
    ('Dev Viewer',   'viewer@opsbridge.local',   '$2a$10$KI30.b/P.BVpjIukycHjXOVa67ma5rr4ejOn3FRz6QemJlVfvWIIW', 'VIEWER',   TRUE),
    ('Dev Operator', 'operator@opsbridge.local', '$2a$10$KwspPZWyHXRp3jfcX9rjoun8AHE14XmwlaoVXQpdKoznR6.Gp4e2K', 'OPERATOR', TRUE),
    ('Dev Admin',    'admin@opsbridge.local',    '$2a$10$FOiaAKzE97JUHiJ9wP4Qn.FkcUU7vBaVnI98blPPfRQDDDNrJkHMy', 'ADMIN',    TRUE);
