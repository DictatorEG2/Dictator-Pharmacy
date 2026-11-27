-- Multi-pharmacy tenant isolation
CREATE TABLE IF NOT EXISTS pharmacies (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('active','suspended'))
);

ALTER TABLE devices ADD COLUMN pharmacy_id TEXT REFERENCES pharmacies(id);
ALTER TABLE invitations ADD COLUMN pharmacy_id TEXT REFERENCES pharmacies(id);
ALTER TABLE audit_log ADD COLUMN pharmacy_id TEXT REFERENCES pharmacies(id);

CREATE INDEX IF NOT EXISTS idx_devices_pharmacy_status_created
  ON devices(pharmacy_id, status, created_at);
CREATE INDEX IF NOT EXISTS idx_invitations_pharmacy_expiry
  ON invitations(pharmacy_id, expires_at);
CREATE INDEX IF NOT EXISTS idx_audit_pharmacy_created
  ON audit_log(pharmacy_id, created_at);

CREATE TABLE IF NOT EXISTS registration_limits (
  ip_hash TEXT PRIMARY KEY,
  window_started_at INTEGER NOT NULL,
  attempts INTEGER NOT NULL
);
