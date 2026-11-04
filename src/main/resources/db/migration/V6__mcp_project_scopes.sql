CREATE TABLE token_projects (
 token_id INTEGER NOT NULL REFERENCES tokens(id),
 project_id INTEGER NOT NULL REFERENCES projects(id),
 PRIMARY KEY(token_id,project_id)
);
INSERT INTO token_projects(token_id,project_id) SELECT id,project_id FROM tokens;
