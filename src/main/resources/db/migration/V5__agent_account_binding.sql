ALTER TABLE users ADD COLUMN account_id INTEGER REFERENCES users(id);
-- Preserve established agents only when their token audit identifies a human creator.
UPDATE users SET account_id=(
 SELECT a.actor_id FROM audit a JOIN tokens t ON a.target=CAST(t.id AS TEXT)
 JOIN users h ON h.id=a.actor_id AND h.kind IN ('OWNER','DEVELOPER')
 WHERE a.action='TOKEN_CREATED' AND t.user_id=users.id ORDER BY a.id LIMIT 1
) WHERE kind='AGENT';
CREATE INDEX users_account ON users(account_id);
