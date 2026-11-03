package dev.forge.tracker.notifications;

import dev.forge.tracker.auth.*;
import dev.forge.tracker.common.Problem;
import dev.forge.tracker.persistence.Database;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
  private final Database db;
  private final Identity identity;

  public NotificationController(Database db, Identity identity) {
    this.db = db;
    this.identity = identity;
  }

  private static final String VISIBLE =
      " AND EXISTS(SELECT 1 FROM users u WHERE u.id=n.user_id AND (u.kind='OWNER' OR EXISTS(SELECT"
          + " 1 FROM members m WHERE m.user_id=u.id AND m.project_id=t.project_id)))";

  @GetMapping
  public Object list(Authentication auth, @RequestParam(defaultValue = "0") int page) {
    Actor actor = identity.actor(auth);
    if (page < 0) throw Problem.bad("Invalid page.");
    String from =
        " FROM notifications n JOIN tasks t ON t.id=n.task_id WHERE n.user_id=?" + VISIBLE;
    return Map.of(
        "items",
        db.rows(
            "SELECT n.*,t.identifier,t.title" + from + " ORDER BY n.id DESC LIMIT 40 OFFSET ?",
            actor.id(),
            (long) page * 40),
        "total",
        db.one(Long.class, "SELECT count(*)" + from, actor.id()),
        "unread",
        db.one(Long.class, "SELECT count(*)" + from + " AND n.unread=1", actor.id()));
  }

  @PostMapping("/{id}/read")
  public Object read(Authentication auth, @PathVariable long id) {
    db.execute(
        "UPDATE notifications SET unread=0 WHERE id=? AND user_id=?",
        id,
        identity.actor(auth).id());
    return Map.of("ok", true);
  }

  @PostMapping("/read-all")
  public Object all(Authentication auth) {
    db.execute("UPDATE notifications SET unread=0 WHERE user_id=?", identity.actor(auth).id());
    return Map.of("ok", true);
  }
}
