CREATE TABLE task_revisions (
 task_id INTEGER NOT NULL REFERENCES tasks(id), revision INTEGER NOT NULL,
 title TEXT NOT NULL, description TEXT NOT NULL, type TEXT NOT NULL, category TEXT NOT NULL,
 author_id INTEGER NOT NULL REFERENCES users(id), created_at TEXT NOT NULL,
 PRIMARY KEY(task_id,revision)
);
INSERT INTO task_revisions(task_id,revision,title,description,type,category,author_id,created_at)
 SELECT id,revision,title,description,type,category,creator_id,updated_at FROM tasks;
