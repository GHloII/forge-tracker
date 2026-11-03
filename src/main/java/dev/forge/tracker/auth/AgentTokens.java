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
            SELECT u.id,u.username,u.kind,t.id AS token_id,t.project_id,t.permission AS token_permission
            FROM tokens t JOIN users u ON u.id=t.user_id WHERE t.hash=? AND t.revoked=0 AND u.enabled=1 AND u.kind='AGENT'
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
    return Map.of("id", id, "token", token);
  }

  @Transactional
  public void revoke(Actor actor, long id) {
    access.admin(actor);
    db.execute("UPDATE tokens SET revoked=1 WHERE id=?", id);
    db.execute(
        "INSERT INTO audit(actor_id,action,target,created_at) VALUES(?,'TOKEN_REVOKED',?,?)",
        actor.id(),
        Long.toString(id),
        Instant.now().toString());
  }
}
