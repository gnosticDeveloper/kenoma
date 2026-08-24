-- VASSAGO_USER/BIME_USER/BIME_MANAGER were renamed (see raum.security.RaumRole /
-- vassago.security.VassagoRole / bime.security.BimeRole) before Flyway migrations existed.
-- The fresh-seed scripts and the reconciled operator row were fixed at the time (commit
-- 0818775), but any user created via onboarding before that rename still carries the old
-- role name strings inside their `roles` JSON blob, which no longer match any enum constant.
-- BIME_MANAGER had the exact same permission set as BIME_ADMIN, so it folds into BIME_ADMIN.
UPDATE users
SET roles = replace(replace(replace(roles,
    '"VASSAGO_USER"', '"VASSAGO_MEMBER"'),
    '"BIME_USER"', '"BIME_CATALOG_VIEWER"'),
    '"BIME_MANAGER"', '"BIME_ADMIN"')
WHERE roles LIKE '%VASSAGO_USER%' OR roles LIKE '%BIME_USER%' OR roles LIKE '%BIME_MANAGER%';
