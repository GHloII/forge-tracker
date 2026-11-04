package dev.forge.tracker.auth;

import dev.forge.tracker.common.Problem;
import dev.forge.tracker.persistence.Database;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentTokens {
  private final Database db;
  private final Access access;

  public AgentTokens(Database db, Access access) {
    this.db = db;
    this.access = access;
  }

  public static String hash(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  public Actor authenticate(String raw) {
    return db.optional(
            Actor.class,
            """
            SELECT u.id,u.username,u.kind,t.id AS token_id,NULL AS project_id,t.permission AS token_permission
            FROM tokens t JOIN users u ON u.id=t.user_id WHERE t.hash=? AND t.revoked=0 AND u.enabled=1 AND u.kind='AGENT'
            AND EXISTS (SELECT 1 FROM users h WHERE h.id=u.account_id AND h.enabled=1 AND h.kind IN ('OWNER','DEVELOPER'))
            AND (t.expires_at IS NULL OR julianday(t.expires_at)>julianday(?))
            """,
            hash(raw),
            Instant.now().toString())
        .orElseThrow(Problem::forbidden);
  }

  @Transactional
  public Map<String, Object> create(
      Actor actor, long user, long project, String name, String permission, String expiresAt) {
    access.admin(actor);
    return issue(actor, user, project, name, permission, expiresAt);
  }

  private Map<String, Object> issue(
      Actor actor, long user, long project, String name, String permission, String expiresAt) {
    if (permission == null
        || !Set.of("READ", "WRITE", "MANAGE").contains(permission)
        || name == null
        || name.isBlank()
        || name.length() > 100) throw Problem.bad("Invalid token name or permission.");
    String expiration = null;
    if (expiresAt != null && !expiresAt.isBlank()) {
      try {
        expiration = Instant.parse(expiresAt).toString();
      } catch (java.time.DateTimeException ex) {
        throw Problem.bad("Expiration must be an ISO-8601 timestamp.");
      }
      if (!Instant.parse(expiration).isAfter(Instant.now()))
        throw Problem.bad("Expiration must be in the future.");
    }
    if (!db.optional(String.class, "SELECT kind FROM users WHERE id=?", user)
        .orElse("")
        .equals("AGENT")) throw Problem.bad("Tokens require an agent identity.");
    access.require(
        new Actor(user, "", "AGENT", null, null, null),
        project,
        Access.Permission.valueOf(permission));
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    String token = "ft_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    db.execute(
        "INSERT INTO tokens(user_id,project_id,name,hash,permission,expires_at,created_at)"
            + " VALUES(?,?,?,?,?,?,?)",
        user,
        project,
        name,
        hash(token),
        permission,
        expiration,
        Instant.now().toString());
    long id = db.id();
    db.execute(
        "INSERT INTO audit(actor_id,action,target,created_at) VALUES(?,'TOKEN_CREATED',?,?)",
        actor.id(),
        Long.toString(id),
        Instant.now().toString());
    db.execute("INSERT INTO token_projects(token_id,project_id) VALUES(?,?)", id, project);
    return Map.of("id", id, "token", token);
  }

  @Transactional
  public Map<String, Object> connect(Actor actor, long account, long project) {
    return connect(actor, account, List.of(project));
  }

  @Transactional
  public Map<String, Object> connect(Actor actor, long account, List<Long> projects) {
    if (actor.agent()) throw Problem.forbidden();
    if (actor.id() != account) access.admin(actor);
    var selected = validateProjects(account, projects);
    if (selected.isEmpty()) throw Problem.bad("Add at least one project.");
    String name = db.one(String.class, "SELECT display_name FROM users WHERE id=?", account);
    Long agent =
        db.optional(
                Long.class,
                "SELECT id FROM users WHERE account_id=? AND kind='AGENT' AND enabled=1 AND"
                    + " username LIKE ? ORDER BY id LIMIT 1",
                account,
                "mcp-" + account + "-%")
            .orElse(null);
    if (agent == null) {
      db.execute(
          "INSERT INTO users(username,display_name,kind,account_id) VALUES(?,?,'AGENT',?)",
          "mcp-" + account + "-" + UUID.randomUUID().toString().substring(0, 8),
          name + " · MCP",
          account);
      agent = db.id();
    }
    for (long project : selected.keySet()) grantAgentProject(agent, project, selected.get(project));
    long first = selected.keySet().iterator().next();
    var result =
        issue(
            actor,
            agent,
            first,
            name.substring(0, Math.min(name.length(), 94)) + " · MCP",
            selected.get(first),
            null);
    long id = ((Number) result.get("id")).longValue();
    db.execute("UPDATE tokens SET permission='MANAGE' WHERE id=?", id);
    for (long project : selected.keySet())
      db.execute(
          "INSERT OR IGNORE INTO token_projects(token_id,project_id) VALUES(?,?)", id, project);
    return result;
  }

  private Map<Long, String> validateProjects(long account, List<Long> projects) {
    if (projects == null || projects.size() > 100 || projects.stream().anyMatch(Objects::isNull))
      throw Problem.bad("Invalid projects.");
    String kind =
        db.optional(
                String.class,
                "SELECT kind FROM users WHERE id=? AND enabled=1 AND kind IN ('OWNER','DEVELOPER')",
                account)
            .orElseThrow(Problem::forbidden);
    var selected = new LinkedHashMap<Long, String>();
    for (long project : projects) {
      if (db.rows("SELECT id FROM projects WHERE id=?", project).isEmpty()) throw Problem.missing();
      access.require(
          new Actor(account, "", kind, null, null, null), project, Access.Permission.READ);
      selected.put(
          project,
          kind.equals("OWNER")
              ? "MANAGE"
              : db.one(
                  String.class,
                  "SELECT permission FROM members WHERE project_id=? AND user_id=?",
                  project,
                  account));
    }
    return selected;
  }

  private void grantAgentProject(long agent, long project, String permission) {
    db.execute(
        "INSERT INTO members(project_id,user_id,permission,role_id) VALUES(?,?,?,(SELECT id FROM"
            + " project_roles WHERE project_id=? AND builtin=1 AND permission=?)) ON"
            + " CONFLICT(project_id,user_id) DO UPDATE SET"
            + " permission=excluded.permission,role_id=excluded.role_id",
        project,
        agent,
        permission,
        project,
        permission);
  }

  @Transactional
  public void projects(Actor actor, long id, List<Long> projects) {
    if (actor.agent()) throw Problem.forbidden();
    var token =
        db
            .rows(
                "SELECT t.user_id,u.account_id FROM tokens t JOIN users u ON u.id=t.user_id WHERE"
                    + " t.id=? AND t.revoked=0",
                id)
            .stream()
            .findFirst()
            .orElseThrow(Problem::missing);
    if (!(token.get("account_id") instanceof Number accountId)) throw Problem.forbidden();
    long account = accountId.longValue();
    if (actor.id() != account) access.admin(actor);
    var selected = validateProjects(account, projects);
    long agent = ((Number) token.get("user_id")).longValue();
    for (long project : selected.keySet()) grantAgentProject(agent, project, selected.get(project));
    db.execute("DELETE FROM token_projects WHERE token_id=?", id);
    for (long project : selected.keySet())
      db.execute("INSERT INTO token_projects(token_id,project_id) VALUES(?,?)", id, project);
    db.execute(
        "INSERT INTO audit(actor_id,action,target,created_at)"
            + " VALUES(?,'TOKEN_PROJECTS_CHANGED',?,?)",
        actor.id(),
        Long.toString(id),
        Instant.now().toString());
  }

  public List<Map<String, Object>> list(Actor actor) {
    if (actor.agent()) throw Problem.forbidden();
    var result =
        db.rows(
            "SELECT t.id,t.name,t.permission,t.expires_at,t.revoked,h.display_name AS"
                + " account_name,p.name AS project_name FROM tokens t JOIN users u ON"
                + " u.id=t.user_id LEFT JOIN users h ON h.id=u.account_id JOIN projects p ON"
                + " p.id=t.project_id WHERE (?=1 OR u.account_id=?) ORDER BY t.id DESC LIMIT 200",
            actor.owner() ? 1 : 0,
            actor.id());
    for (var token : result)
      token.put(
          "projects",
          db.rows(
              "SELECT p.id,p.name FROM token_projects tp JOIN projects p ON p.id=tp.project_id"
                  + " WHERE tp.token_id=? ORDER BY p.name LIMIT 100",
              token.get("id")));
    return result;
  }

  @Transactional
  public void revoke(Actor actor, long id) {
    if (actor.agent()
        || (!actor.owner()
            && db.rows(
                    "SELECT t.id FROM tokens t JOIN users u ON u.id=t.user_id WHERE t.id=? AND"
                        + " u.account_id=?",
                    id,
                    actor.id())
                .isEmpty())) throw Problem.forbidden();
    db.execute("UPDATE tokens SET revoked=1 WHERE id=?", id);
    db.execute(
        "INSERT INTO audit(actor_id,action,target,created_at) VALUES(?,'TOKEN_REVOKED',?,?)",
        actor.id(),
        Long.toString(id),
        Instant.now().toString());
  }
}
