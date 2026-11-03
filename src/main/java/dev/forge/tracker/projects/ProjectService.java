package dev.forge.tracker.projects;

import dev.forge.tracker.auth.*;
import dev.forge.tracker.common.Problem;
import dev.forge.tracker.persistence.Database;
import java.util.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ProjectService {
  private final Database db;
  private final Access access;
  private final PasswordEncoder passwords;

  public ProjectService(Database db, Access access, PasswordEncoder passwords) {
    this.db = db;
    this.access = access;
    this.passwords = passwords;
  }

  public List<Map<String, Object>> list(Actor actor) {
    if (actor.owner()) return db.rows("SELECT * FROM projects ORDER BY name LIMIT 100");
    return db.rows(
        "SELECT p.* FROM projects p JOIN members m ON m.project_id=p.id WHERE m.user_id=? AND (? IS"
            + " NULL OR p.id=?) ORDER BY p.name LIMIT 100",
        actor.id(),
        actor.projectId(),
        actor.projectId());
  }

  public Map<String, Object> context(Actor actor, long id) {
    access.require(actor, id, Access.Permission.READ);
    return Map.of(
        "project",
        db.rows("SELECT * FROM projects WHERE id=?", id).stream()
            .findFirst()
            .orElseThrow(Problem::missing),
        "participants",
        participants(actor, id));
  }

  public List<Map<String, Object>> participants(Actor actor, long id) {
    access.require(actor, id, Access.Permission.READ);
    return db.rows(
        "SELECT u.id,u.username,u.display_name,u.kind,coalesce(m.permission,'MANAGE') AS permission"
            + " FROM users u LEFT JOIN members m ON m.user_id=u.id AND m.project_id=? WHERE"
            + " u.enabled=1 AND (u.kind='OWNER' OR m.user_id IS NOT NULL) ORDER BY u.display_name"
            + " LIMIT 100",
        id);
  }

  public long create(Actor actor, String key, String name, String description, String repository) {
    access.admin(actor);
    validate(key, name, description, repository);
    db.execute(
        "INSERT INTO projects(key,name,description,repository) VALUES(?,?,?,?)",
        key,
        name,
        description,
        repository);
    return db.id();
  }

  public void update(
      Actor actor, long id, String key, String name, String description, String repository) {
    access.admin(actor);
    validate(key, name, description, repository);
    String current = db.one(String.class, "SELECT key FROM projects WHERE id=?", id);
    if (!current.equals(key)) throw Problem.bad("Project keys are permanent.");
    db.execute(
        "UPDATE projects SET name=?,description=?,repository=? WHERE id=?",
        name,
        description,
        repository,
        id);
  }

  private void validate(String key, String name, String description, String repository) {
    if (key == null
        || !key.matches("[A-Z][A-Z0-9]{1,9}")
        || name == null
        || name.isBlank()
        || name.length() > 100
        || description == null
        || description.length() > 20000
        || repository == null
        || (!repository.isBlank()
            && !repository.matches("https://github\\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")))
      throw Problem.bad(
          "Use a 2-10 character uppercase project key, a name and an optional HTTPS GitHub"
              + " repository URL.");
  }

  public void member(Actor actor, long project, long user, String permission) {
    access.admin(actor);
    if (permission == null || !Set.of("READ", "WRITE", "MANAGE", "REMOVE").contains(permission))
      throw Problem.bad("Invalid permission.");
    if (permission.equals("REMOVE"))
      db.execute("DELETE FROM members WHERE project_id=? AND user_id=?", project, user);
    else
      db.execute(
          "INSERT INTO members(project_id,user_id,permission) VALUES(?,?,?) ON"
              + " CONFLICT(project_id,user_id) DO UPDATE SET permission=excluded.permission",
          project,
          user,
          permission);
  }

  public List<Map<String, Object>> users(Actor actor) {
    access.admin(actor);
    return db.rows("SELECT id,username,display_name,kind,enabled FROM users ORDER BY id LIMIT 200");
  }

  public long user(Actor actor, String username, String displayName, String kind, String password) {
    access.admin(actor);
    if (username == null
        || !username.matches("[A-Za-z0-9_.-]{1,40}")
        || displayName == null
        || displayName.isBlank()
        || displayName.length() > 100
        || kind == null
        || !Set.of("OWNER", "DEVELOPER", "AGENT").contains(kind))
      throw Problem.bad("Invalid username, display name or role.");
    if (!kind.equals("AGENT")) validatePassword(password);
    db.execute(
        "INSERT INTO users(username,display_name,kind,password_hash) VALUES(?,?,?,?)",
        username,
        displayName,
        kind,
        kind.equals("AGENT") ? null : passwords.encode(password));
    return db.id();
  }

  public void updateUser(Actor actor, long user, boolean enabled, String password) {
    access.admin(actor);
    if (user == actor.id() && !enabled) throw Problem.bad("You cannot disable your own account.");
    db.execute("UPDATE users SET enabled=? WHERE id=?", enabled ? 1 : 0, user);
    if (password != null && !password.isEmpty()) {
      validatePassword(password);
      db.execute(
          "UPDATE users SET password_hash=? WHERE id=? AND kind<>'AGENT'",
          passwords.encode(password),
          user);
    }
  }

  public void changePassword(Actor actor, String current, String replacement) {
    validatePassword(replacement);
    String hash = db.one(String.class, "SELECT password_hash FROM users WHERE id=?", actor.id());
    if (hash == null || current == null || !passwords.matches(current, hash))
      throw Problem.forbidden();
    db.execute(
        "UPDATE users SET password_hash=? WHERE id=?", passwords.encode(replacement), actor.id());
  }

  private void validatePassword(String password) {
    if (password == null
        || password.length() < 12
        || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
      throw Problem.bad("Passwords require at least 12 characters and at most 72 UTF-8 bytes.");
  }
}
