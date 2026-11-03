CREATE TABLE users (
 id INTEGER PRIMARY KEY, username TEXT NOT NULL UNIQUE COLLATE NOCASE,
 display_name TEXT NOT NULL, password_hash TEXT,
 kind TEXT NOT NULL CHECK(kind IN ('OWNER','DEVELOPER','AGENT')), enabled INTEGER NOT NULL DEFAULT 1
);
CREATE TABLE projects (
 id INTEGER PRIMARY KEY, key TEXT NOT NULL UNIQUE, name TEXT NOT NULL,
 description TEXT NOT NULL DEFAULT '', repository TEXT NOT NULL DEFAULT '', next_number INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE members (
 project_id INTEGER NOT NULL REFERENCES projects(id), user_id INTEGER NOT NULL REFERENCES users(id),
 permission TEXT NOT NULL CHECK(permission IN ('READ','WRITE','MANAGE')), PRIMARY KEY(project_id,user_id)
);
CREATE TABLE tokens (
 id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id), project_id INTEGER NOT NULL REFERENCES projects(id),
 name TEXT NOT NULL, hash TEXT NOT NULL UNIQUE, permission TEXT NOT NULL CHECK(permission IN ('READ','WRITE','MANAGE')),
 expires_at TEXT, revoked INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL
);
CREATE TABLE tasks (
 id INTEGER PRIMARY KEY, project_id INTEGER NOT NULL REFERENCES projects(id), identifier TEXT NOT NULL UNIQUE,
 title TEXT NOT NULL CHECK(length(title) BETWEEN 1 AND 240), description TEXT NOT NULL DEFAULT '',
 type TEXT NOT NULL CHECK(type IN ('FEATURE','BUG','IMPROVEMENT','RESEARCH')),
 priority TEXT NOT NULL CHECK(priority IN ('URGENT','HIGH','MEDIUM','LOW','NONE')),
 status TEXT NOT NULL CHECK(status IN ('BACKLOG','READY','IN_PROGRESS','IN_REVIEW','DONE','CANCELLED')),
 approval TEXT NOT NULL CHECK(approval IN ('PENDING','APPROVED','CHANGES_REQUESTED','REJECTED')),
 creator_id INTEGER NOT NULL REFERENCES users(id), assignee_id INTEGER REFERENCES users(id),
 category TEXT NOT NULL DEFAULT '', labels TEXT NOT NULL DEFAULT '[]', archived INTEGER NOT NULL DEFAULT 0,
 version INTEGER NOT NULL DEFAULT 1, revision INTEGER NOT NULL DEFAULT 1, created_at TEXT NOT NULL, updated_at TEXT NOT NULL
);
CREATE INDEX tasks_project_status ON tasks(project_id,archived,status,updated_at);
CREATE INDEX tasks_assignee ON tasks(assignee_id,archived,updated_at);
CREATE INDEX tasks_approval ON tasks(project_id,approval,archived);
CREATE VIRTUAL TABLE task_search USING fts5(identifier,title,description,content='tasks',content_rowid='id');
CREATE TRIGGER tasks_ai AFTER INSERT ON tasks BEGIN
 INSERT INTO task_search(rowid,identifier,title,description) VALUES(new.id,new.identifier,new.title,new.description);
END;
CREATE TRIGGER tasks_au AFTER UPDATE ON tasks BEGIN
 INSERT INTO task_search(task_search,rowid,identifier,title,description) VALUES('delete',old.id,old.identifier,old.title,old.description);
 INSERT INTO task_search(rowid,identifier,title,description) VALUES(new.id,new.identifier,new.title,new.description);
END;
CREATE TABLE approvals (
 id INTEGER PRIMARY KEY, task_id INTEGER NOT NULL REFERENCES tasks(id), reviewer_id INTEGER NOT NULL REFERENCES users(id),
 decision TEXT NOT NULL, revision INTEGER NOT NULL, comment TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL
);
CREATE TABLE comments (
 id INTEGER PRIMARY KEY, task_id INTEGER NOT NULL REFERENCES tasks(id), author_id INTEGER NOT NULL REFERENCES users(id),
 body TEXT NOT NULL, created_at TEXT NOT NULL
);
CREATE INDEX comments_task ON comments(task_id,id);
CREATE TABLE subscriptions (
 task_id INTEGER NOT NULL REFERENCES tasks(id), user_id INTEGER NOT NULL REFERENCES users(id), PRIMARY KEY(task_id,user_id)
);
CREATE TABLE activity (
 id INTEGER PRIMARY KEY, task_id INTEGER NOT NULL REFERENCES tasks(id), actor_id INTEGER NOT NULL REFERENCES users(id),
 event TEXT NOT NULL, detail TEXT NOT NULL, created_at TEXT NOT NULL
);
CREATE INDEX activity_task ON activity(task_id,id);
CREATE TABLE notifications (
 id INTEGER PRIMARY KEY, event_id INTEGER NOT NULL REFERENCES activity(id), user_id INTEGER NOT NULL REFERENCES users(id),
 task_id INTEGER NOT NULL REFERENCES tasks(id), message TEXT NOT NULL, unread INTEGER NOT NULL DEFAULT 1, created_at TEXT NOT NULL,
 UNIQUE(event_id,user_id)
);
CREATE INDEX notifications_user ON notifications(user_id,unread,id);
CREATE TABLE links (
 id INTEGER PRIMARY KEY, task_id INTEGER NOT NULL REFERENCES tasks(id), kind TEXT NOT NULL CHECK(kind IN ('PR','COMMIT','RELATED')),
 url TEXT NOT NULL, UNIQUE(task_id,kind,url)
);
CREATE TABLE attachments (
 id TEXT PRIMARY KEY, task_id INTEGER NOT NULL REFERENCES tasks(id), filename TEXT NOT NULL, media_type TEXT NOT NULL,
 size INTEGER NOT NULL, created_at TEXT NOT NULL
);
CREATE TABLE imports (
 project_id INTEGER NOT NULL REFERENCES projects(id), source TEXT NOT NULL, fingerprint TEXT NOT NULL,
 task_id INTEGER NOT NULL REFERENCES tasks(id), PRIMARY KEY(project_id,source,fingerprint)
);
CREATE TABLE audit (
 id INTEGER PRIMARY KEY, actor_id INTEGER REFERENCES users(id), token_id INTEGER REFERENCES tokens(id),
 action TEXT NOT NULL, target TEXT NOT NULL, created_at TEXT NOT NULL
);
