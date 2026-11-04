package dev.forge.tracker.auth;

import dev.forge.tracker.common.Problem;
import dev.forge.tracker.persistence.Database;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class Access {
  public enum Permission {
    READ,
    WRITE,
    MANAGE,
    APPROVE,
    ADMIN
  }

  private final Database db;

  public Access(Database db) {
    this.db = db;
  }

  public void require(Actor actor, long project, Permission permission) {
    if (actor == null) throw Problem.forbidden();
    if (actor.agent()) {
      long account =
          db.optional(
                  Long.class,
                  "SELECT h.id FROM users a JOIN users h ON h.id=a.account_id WHERE a.id=?"
                      + " AND a.enabled=1 AND h.enabled=1 AND h.kind IN ('OWNER','DEVELOPER')",
                  actor.id())
              .orElseThrow(Problem::forbidden);
      String kind = db.one(String.class, "SELECT kind FROM users WHERE id=?", account);
      require(new Actor(account, "", kind, null, null, null), project, permission);
    }
    if (actor.owner()) return;
    if (permission == Permission.APPROVE || permission == Permission.ADMIN)
      throw Problem.forbidden();
    if (actor.tokenId() != null) {
      var token =
          db.rows(
              "SELECT t.* FROM tokens t JOIN users u ON u.id=t.user_id WHERE t.id=? AND t.user_id=?"
                  + " AND t.revoked=0 AND u.enabled=1 AND (t.expires_at IS NULL OR"
                  + " julianday(t.expires_at)>julianday('now'))",
              actor.tokenId(),
              actor.id());
      if (token.isEmpty()
          || db.rows(
                  "SELECT project_id FROM token_projects WHERE token_id=? AND project_id=?",
                  actor.tokenId(),
                  project)
              .isEmpty()
          || rank(String.valueOf(token.getFirst().get("permission"))) < rank(permission.name()))
        throw Problem.forbidden();
    }
    String member =
        db.optional(
                String.class,
                "SELECT permission FROM members WHERE project_id=? AND user_id=?",
                project,
                actor.id())
            .orElse("");
    if (rank(member) < rank(permission.name())) throw Problem.forbidden();
  }

  private int rank(String permission) {
    return Map.of("READ", 1, "WRITE", 2, "MANAGE", 3).getOrDefault(permission, 0);
  }

  public void admin(Actor actor) {
    if (actor == null || !actor.owner()) throw Problem.forbidden();
  }
}
