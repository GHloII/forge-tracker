CREATE TABLE project_roles (
 id INTEGER PRIMARY KEY,
 project_id INTEGER NOT NULL REFERENCES projects(id),
 name TEXT NOT NULL COLLATE NOCASE CHECK(length(name) BETWEEN 1 AND 60),
 permission TEXT NOT NULL CHECK(permission IN ('READ','WRITE','MANAGE')),
 builtin INTEGER NOT NULL DEFAULT 0,
 UNIQUE(project_id,name)
);
INSERT INTO project_roles(project_id,name,permission,builtin)
 SELECT id,'Viewer','READ',1 FROM projects;
INSERT INTO project_roles(project_id,name,permission,builtin)
 SELECT id,'Developer','WRITE',1 FROM projects;
INSERT INTO project_roles(project_id,name,permission,builtin)
 SELECT id,'Manager','MANAGE',1 FROM projects;
CREATE TRIGGER projects_default_roles AFTER INSERT ON projects BEGIN
 INSERT INTO project_roles(project_id,name,permission,builtin) VALUES(new.id,'Viewer','READ',1);
 INSERT INTO project_roles(project_id,name,permission,builtin) VALUES(new.id,'Developer','WRITE',1);
 INSERT INTO project_roles(project_id,name,permission,builtin) VALUES(new.id,'Manager','MANAGE',1);
END;
ALTER TABLE members ADD COLUMN role_id INTEGER REFERENCES project_roles(id);
UPDATE members SET role_id=(SELECT id FROM project_roles r
 WHERE r.project_id=members.project_id AND r.permission=members.permission AND r.builtin=1);
CREATE INDEX members_role ON members(role_id);
CREATE TRIGGER project_role_permission_updated AFTER UPDATE OF permission ON project_roles BEGIN
 UPDATE members SET permission=new.permission WHERE role_id=new.id;
END;
ALTER TABLE invitations ADD COLUMN project_id INTEGER REFERENCES projects(id);
ALTER TABLE invitations ADD COLUMN role_id INTEGER REFERENCES project_roles(id);
CREATE INDEX invitations_project ON invitations(project_id,id);
