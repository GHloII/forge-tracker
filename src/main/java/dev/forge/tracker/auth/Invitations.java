package dev.forge.tracker.auth;

import dev.forge.tracker.common.Problem;
import dev.forge.tracker.persistence.Database;
import dev.forge.tracker.projects.ProjectRoles;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Invitations {
  private final Database db;
  private final Access access;
  private final PasswordEncoder passwords;
  private final ProjectRoles roles;

  public Invitations(Database db, Access access, PasswordEncoder passwords, ProjectRoles roles) {
    this.db = db;
    this.access = access;
    this.passwords = passwords;
    this.roles = roles;
  }

  public Object list(Actor actor, long project) {
    access.admin(actor);
    return db.rows("""
        SELECT i.id,i.created_at,i.expires_at,i.revoked,i.used_by,i.role_id,r.name AS role_name,
               r.permission FROM invitations i LEFT JOIN project_roles r ON r.id=i.role_id
        WHERE i.project_id=? ORDER BY i.id DESC LIMIT 100
        """, project);
  }

  @Transactional
  public Map<String, Object> create(Actor actor, long project, long role) {
    access.admin(actor);
    roles.requireRole(project, role);
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    String now = Instant.now().toString();
    String expires = Instant.now().plus(7, ChronoUnit.DAYS).toString();
    db.execute("INSERT INTO invitations(hash,creator_id,created_at,expires_at,project_id,role_id) VALUES(?,?,?,?,?,?)",
        AgentTokens.hash(token), actor.id(), now, expires, project, role);
    long id = db.id();
    db.execute("INSERT INTO audit(actor_id,action,target,created_at) VALUES(?,'INVITATION_CREATED',?,?)", actor.id(), Long.toString(id), now);
    return Map.of("id", id, "token", token, "expires_at", expires, "project_id", project, "role_id", role);
  }

  @Transactional
  public void revoke(Actor actor, long id) {
    access.admin(actor);
    db.execute("UPDATE invitations SET revoked=1 WHERE id=?", id);
    db.execute("INSERT INTO audit(actor_id,action,target,created_at) VALUES(?,'INVITATION_REVOKED',?,?)", actor.id(), Long.toString(id), Instant.now().toString());
  }

  @Transactional
  public void revoke(Actor actor, long project, long id) {
    access.admin(actor);
    if (db.one(Long.class, "SELECT count(*) FROM invitations WHERE project_id=? AND id=?", project, id) != 1)
      throw Problem.missing();
    revoke(actor, id);
  }

  @Transactional
  public Map<String, Object> register(String token, String username, String displayName, String password) {
    var invite = available(token);
    if (username == null || !username.matches("[A-Za-z0-9_.-]{1,40}") || displayName == null || displayName.isBlank() || displayName.length()>100)
      throw Problem.bad("Enter a valid username and display name.");
    if (password == null || password.length()<12 || password.getBytes(StandardCharsets.UTF_8).length>72)
      throw Problem.bad("Passwords require at least 12 characters and at most 72 UTF-8 bytes.");
    if (db.one(Long.class, "SELECT count(*) FROM users WHERE username=?", username)>0)
      throw Problem.conflict("Username is already taken.");
    db.execute("INSERT INTO users(username,display_name,password_hash,kind) VALUES(?,?,?,'DEVELOPER')", username, displayName.strip(), passwords.encode(password));
    long user = db.id();
    return consume(invite, user);
  }

  @Transactional
  public Map<String, Object> accept(Actor actor, String token) {
    if (actor.agent() || actor.tokenId() != null) throw Problem.forbidden();
    return consume(available(token), actor.id());
  }

  private Map<String, Object> available(String token) {
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}"))
      throw Problem.bad("Invitation is invalid or no longer available.");
    return db.rows("""
        SELECT i.id,i.project_id,i.role_id FROM invitations i JOIN users u ON u.id=i.creator_id
        WHERE i.hash=? AND i.revoked=0 AND i.used_by IS NULL
        AND julianday(i.expires_at)>julianday('now') AND u.enabled=1 AND u.kind='OWNER'
        """, AgentTokens.hash(token)).stream().findFirst()
        .orElseThrow(() -> Problem.bad("Invitation is invalid or no longer available."));
  }

  private Map<String, Object> consume(Map<String, Object> invite, long user) {
    Object id = invite.get("id");
    if (db.execute("UPDATE invitations SET used_by=? WHERE id=? AND used_by IS NULL AND revoked=0 AND julianday(expires_at)>julianday('now')", user, id)!=1)
      throw Problem.conflict("Invitation is no longer available.");
    Object project = invite.get("project_id"), role = invite.get("role_id");
    if (project != null) {
      if (!(role instanceof Number roleId)) throw Problem.bad("Invitation role is no longer available.");
      String permission = roles.requireRole(((Number) project).longValue(), roleId.longValue());
      db.execute("""
          INSERT INTO members(project_id,user_id,permission,role_id) VALUES(?,?,?,?)
          ON CONFLICT(project_id,user_id) DO NOTHING
          """, project, user, permission, role);
    }
    db.execute("INSERT INTO audit(actor_id,action,target,created_at) VALUES(?,'INVITATION_ACCEPTED',?,?)", user, id.toString(), Instant.now().toString());
    return project == null ? Map.of("ok", true) : Map.of("ok", true, "project_id", project);
  }
}
