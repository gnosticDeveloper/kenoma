
ALTER TABLE users ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON users
    USING (org_id = current_setting('app.org_id', true)::uuid);

ALTER TABLE pending_verifications ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pending_verifications
    USING (EXISTS (
        SELECT 1 FROM users
        WHERE users.id = pending_verifications.user_id
          AND users.org_id = current_setting('app.org_id', true)::uuid
    ));
