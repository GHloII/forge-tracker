package dev.forge.tracker.auth;

import dev.forge.tracker.common.Problem;
import dev.forge.tracker.persistence.Database;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class Identity {
  private final Database db;

  public Identity(Database db) {
    this.db = db;
  }

  public Actor actor(Authentication auth) {
    if (auth == null || !auth.isAuthenticated()) throw Problem.forbidden();
    if (auth.getPrincipal() instanceof Actor actor) return actor;
    return db.optional(
            Actor.class,
            "SELECT id,username,kind,NULL AS token_id,NULL AS project_id,NULL AS token_permission"
                + " FROM users WHERE username=? AND enabled=1 AND kind<>'AGENT'",
            auth.getName())
        .orElseThrow(Problem::forbidden);
  }
}
