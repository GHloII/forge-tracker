CREATE TABLE invitations (
 id INTEGER PRIMARY KEY, hash TEXT NOT NULL UNIQUE,
 creator_id INTEGER NOT NULL REFERENCES users(id),
 created_at TEXT NOT NULL, expires_at TEXT NOT NULL,
 revoked INTEGER NOT NULL DEFAULT 0,
 used_by INTEGER REFERENCES users(id)
);
