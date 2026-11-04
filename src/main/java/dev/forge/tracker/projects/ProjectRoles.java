package dev.forge.tracker.projects;

import dev.forge.tracker.auth.*;
import dev.forge.tracker.common.Problem;
import dev.forge.tracker.persistence.Database;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ProjectRoles {
  private final Database db;
  private final Access access;

  public ProjectRoles(Database db, Access access) {
    this.db = db;
    this.access = access;
  }

  public List<Map<String, Object>> list(Actor actor, long project) {
    access.require(actor, project, Access.Permission.READ);
    requireProject(project);
    return db.rows(
        "SELECT id,name,permission,builtin FROM project_roles WHERE project_id=? ORDER BY builtin DESC,id LIMIT 100",
        project);
  }

  public long create(Actor actor, long project, String name, String permission) {
    access.admin(actor);
    requireProject(project);
    validate(project, null, name, permission);
    if (db.one(Long.class, "SELECT count(*) FROM project_roles WHERE project_id=?", project) >= 100)
      throw Problem.bad("A project can have up to 100 roles.");
    db.execute("INSERT INTO project_roles(project_id,name,permission) VALUES(?,?,?)",
        project, name.strip(), permission);
    long id = db.id();
    audit(actor, "PROJECT_ROLE_CREATED", project, id);
    return id;
  }

  public void update(Actor actor, long project, long role, String name, String permission) {
    access.admin(actor);
    requireRole(project, role);
    if (db.one(Integer.class, "SELECT builtin FROM project_roles WHERE id=?", role) != 0)
      throw Problem.bad("Default roles cannot be edited. Create a custom role instead.");
    validate(project, role, name, permission);
    db.execute("UPDATE project_roles SET name=?,permission=? WHERE project_id=? AND id=?",
        name.strip(), permission, project, role);
    audit(actor, "PROJECT_ROLE_UPDATED", project, role);
  }

  public void assign(Actor actor, long project, long user, long role) {
    access.admin(actor);
    String permission = requireRole(project, role);
    if (db.one(Long.class, "SELECT count(*) FROM users WHERE id=? AND kind<>'OWNER'", user) != 1)
      throw Problem.bad("Select a developer or agent. Owners already have full access.");
    db.execute("""
        INSERT INTO members(project_id,user_id,permission,role_id) VALUES(?,?,?,?)
        ON CONFLICT(project_id,user_id) DO UPDATE SET permission=excluded.permission,role_id=excluded.role_id
        """, project, user, permission, role);
    audit(actor, "PROJECT_MEMBER_ROLE_CHANGED", project, user);
  }

  public String requireRole(long project, long role) {
    return db.optional(String.class,
        "SELECT permission FROM project_roles WHERE project_id=? AND id=?", project, role)
        .orElseThrow(() -> Problem.bad("Role does not belong to this project."));
  }

  private void requireProject(long project) {
    if (db.one(Long.class, "SELECT count(*) FROM projects WHERE id=?", project) != 1)
      throw Problem.missing();
  }

  private void validate(long project, Long role, String name, String permission) {
    if (name == null || name.isBlank() || name.strip().length() > 60
        || !Set.of("READ", "WRITE", "MANAGE").contains(permission == null ? "" : permission))
      throw Problem.bad("Enter a role name (up to 60 characters) and a valid permission.");
    if (db.one(Long.class, "SELECT count(*) FROM project_roles WHERE project_id=? AND name=? AND (? IS NULL OR id<>?)",
        project, name.strip(), role, role) != 0)
      throw Problem.conflict("A role with this name already exists in this project.");
  }

  private void audit(Actor actor, String action, long project, long target) {
    db.execute("INSERT INTO audit(actor_id,action,target,created_at) VALUES(?,?,?,?)",
        actor.id(), action, project + ":" + target, Instant.now().toString());
  }
}
