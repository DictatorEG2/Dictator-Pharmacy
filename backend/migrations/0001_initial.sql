-- Dictator Pharmacy sync schema
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS pharmacy_state (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  manager_device_id TEXT,
  created_at INTEGER NOT NULL
);
INSERT OR IGNORE INTO pharmacy_state (id, manager_device_id, created_at)
VALUES (1, NULL, unixepoch());

CREATE TABLE IF NOT EXISTS devices (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  role TEXT NOT NULL CHECK (role IN ('manager','staff')),
  status TEXT NOT NULL CHECK (status IN ('active','pending','revoked')),
  token_hash TEXT UNIQUE,
  created_at INTEGER NOT NULL,
  approved_at INTEGER,
  last_seen_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_devices_status_created ON devices(status, created_at);

CREATE TABLE IF NOT EXISTS invitations (
  code_hash TEXT PRIMARY KEY,
  created_by TEXT NOT NULL REFERENCES devices(id),
  expires_at INTEGER NOT NULL,
  used_at INTEGER,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_invitations_expiry ON invitations(expires_at);

CREATE TABLE IF NOT EXISTS audit_log (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  actor_device_id TEXT,
  action TEXT NOT NULL,
  target_id TEXT,
  created_at INTEGER NOT NULL,
  details_json TEXT
);
CREATE INDEX IF NOT EXISTS idx_audit_created ON audit_log(created_at);
