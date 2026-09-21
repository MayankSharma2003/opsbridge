-- Rotates the three seeded dev accounts before this database is reachable from the public
-- internet. Additive, per project convention -- V2 itself is never edited.

UPDATE users SET password_hash = '$2a$10$OdFmRvK2TI8xZncIPB0E/ewe8MF193eHc6p2.xr.999jwas1lu7tS' WHERE email = 'viewer@opsbridge.local';
UPDATE users SET password_hash = '$2a$10$.WT/FM4TQYZe8E3U7mAvzOon4J5XGYU8vZNNCp1v7G/NpJ84VHnMG' WHERE email = 'operator@opsbridge.local';
UPDATE users SET password_hash = '$2a$10$UOvMNQkX8H/eOet/7to7z.O4Htaa9hzCSHfNELD9sTjLJBLK48rxm' WHERE email = 'admin@opsbridge.local';
