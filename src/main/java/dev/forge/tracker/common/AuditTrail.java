package dev.forge.tracker.common;

import dev.forge.tracker.auth.Actor;
import dev.forge.tracker.persistence.Database;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditTrail {
  private final Database db;

  public AuditTrail(Database db) {
    this.db = db;
  }

  @Transactional
  public void invocation(Actor actor, String tool) {
    db.execute(
        "INSERT INTO audit(actor_id,token_id,action,target,created_at) VALUES(?,?,?,?,?)",
        actor.id(),
        actor.tokenId(),
        "MCP_INVOCATION",
        tool,
        Instant.now().toString());
  }
}
