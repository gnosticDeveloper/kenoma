INSERT INTO services (name, description)
VALUES
    ('Raum', 'Credential and organization registry'),
    ('Vassago', 'Authentication and identity service'),
    ('Bime', 'Inventory management service')
ON CONFLICT (name) DO NOTHING;

INSERT INTO organizations (name, contact_name, contact_email)
VALUES ('Platform', 'Platform Operator', 'platform@internal')
ON CONFLICT (name) DO NOTHING;

INSERT INTO credentials (org_id, service_id, db_engine, db_host, db_port, db_name)
SELECT
    o.id,
    s.id,
    'postgres',
    'vassago-postgres',
    5432,
    'vassago'
FROM organizations o, services s
WHERE o.name = 'Platform' AND s.name = 'Vassago'
ON CONFLICT (org_id, service_id) DO NOTHING;

INSERT INTO credentials (org_id, service_id, db_engine, db_host, db_port, db_name)
SELECT
    o.id,
    s.id,
    'postgres',
    'bime-postgres',
    5432,
    'bime'
FROM organizations o, services s
WHERE o.name = 'Platform' AND s.name = 'Bime'
ON CONFLICT (org_id, service_id) DO NOTHING;

INSERT INTO base_pricing (price, currency, effective_from)
SELECT 0, 'USD', current_timestamp
WHERE NOT EXISTS (SELECT 1 FROM base_pricing);

INSERT INTO module_pricing (service_id, price, currency, included_in_base, effective_from)
SELECT s.id, 0, 'USD', true, current_timestamp
FROM services s
WHERE s.name IN ('Raum', 'Vassago', 'Bime')
  AND NOT EXISTS (SELECT 1 FROM module_pricing mp WHERE mp.service_id = s.id);
