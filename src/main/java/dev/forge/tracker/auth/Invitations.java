package dev.forge.tracker.auth;

import dev.forge.tracker.common.Problem;
import dev.forge.tracker.persistence.Database;
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

  public Invitations(Database db, Access access, PasswordEncoder passwords) {
    this.db = db;
    this.access = access;
    this.passwords = passwords;
  }

  public Object list(Actor actor) {
    access.admin(actor);
    return db.rows("SELECT id,created_at,expires_at,revoked,used_by FROM invitations ORDER BY id DESC LIMIT 100");
  }

  @Transactional
  public Object create(Actor actor) {
    access.admin(actor);
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    String now = Instant.now().toString();
    String expires = Instant.now().plus(7, ChronoUnit.DAYS).toString();
    db.execute("INSERT INTO invitations(hash,creator_id,created_at,expires_at) VALUES(?,?,?,?)",
        AgentTokens.hash(token), actor.id(), now, expires);
    long id = db.id();
    db.execute("INSERT INTO audit(actor_id,action,target,created_at) VALUES(?,'INVITATION_CREATED',?,?)", actor.id(), Long.toString(id), now);
    return Map.of("id", id, "token", token, "expires_at", expires);
  }

  @Transactional
  public void revoke(Actor actor, long id) {
    access.admin(actor);
    db.execute("UPDATE invitations SET revoked=1 WHERE id=?", id);
    db.execute("INSERT INTO audit(actor_id,action,target,created_at) VALUES(?,'INVITATION_REVOKED',?,?)", actor.id(), Long.toString(id), Instant.now().toString());
  }

  @Transactional
  public void register(String token, String username, String displayName, String password) {
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw Problem.bad("Invitation is invalid or no longer available.");
    var invite = db.rows("SELECT i.id FROM invitations i JOIN users u ON u.id=i.creator_id WHERE i.hash=? AND i.revoked=0 AND i.used_by IS NULL AND julianday(i.expires_at)>julianday('now') AND u.enabled=1 AND u.kind='OWNER'", AgentTokens.hash(token));
    if (invite.isEmpty()) throw Problem.bad("Invitation is invalid or no longer available.");
    if (username == null || !username.matches("[A-Za-z0-9_.-]{1,40}") || displayName == null || displayName.isBlank() || displayName.length()>100)
      throw Problem.bad("Enter a valid username and display name.");
    if (password == null || password.length()<12 || password.getBytes(StandardCharsets.UTF_8).length>72)
      throw Problem.bad("Passwords require at least 12 characters and at most 72 UTF-8 bytes.");
    if (db.one(Long.class, "SELECT count(*) FROM users WHERE username=?", username)>0)
      throw Problem.conflict("Username is already taken.");
    db.execute("INSERT INTO users(username,display_name,password_hash,kind) VALUES(?,?,?,'DEVELOPER')", username, displayName.strip(), passwords.encode(password));
    long user = db.id();
    Object id = invite.getFirst().get("id");
    if (db.execute("UPDATE invitations SET used_by=? WHERE id=? AND used_by IS NULL AND revoked=0 AND julianday(expires_at)>julianday('now')", user, id)!=1)
      throw Problem.conflict("Invitation is no longer available.");
    db.execute("INSERT INTO audit(actor_id,action,target,created_at) VALUES(?,'INVITATION_ACCEPTED',?,?)", user, id.toString(), Instant.now().toString());
  }
}
