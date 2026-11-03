package dev.forge.tracker.notifications;

import dev.forge.tracker.auth.Actor;
import dev.forge.tracker.persistence.Database;
import dev.forge.tracker.tasks.Task;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class Events {
  private final Database db;

  public Events(Database db) {
    this.db = db;
  }

  public void emit(
      Actor actor, Task task, String event, String detail, boolean reviewers, Long... recipients) {
    String now = Instant.now().toString();
    db.execute(
        "INSERT INTO activity(task_id,actor_id,event,detail,created_at) VALUES(?,?,?,?,?)",
        task.id(),
        actor.id(),
        event,
        detail,
        now);
    long id = db.id();
    Set<Long> users = new HashSet<>();
    users.addAll(
        db.list(Long.class, "SELECT user_id FROM subscriptions WHERE task_id=?", task.id()));
    users.addAll(Arrays.stream(recipients).filter(Objects::nonNull).toList());
    if (reviewers)
      users.addAll(db.list(Long.class, "SELECT id FROM users WHERE kind='OWNER' AND enabled=1"));
    var mentions = Pattern.compile("(?<![\\w@])@([A-Za-z0-9_.-]{1,40})").matcher(detail);
    while (mentions.find())
      db.optional(
              Long.class, "SELECT id FROM users WHERE username=? AND enabled=1", mentions.group(1))
          .ifPresent(users::add);
    users.remove(actor.id());
    for (long user : users) {
      if (db.one(
              Long.class,
              "SELECT count(*) FROM users u WHERE u.id=? AND u.enabled=1 AND (u.kind='OWNER' OR"
                  + " EXISTS(SELECT 1 FROM members m WHERE m.user_id=u.id AND m.project_id=?))",
              user,
              task.projectId())
          == 0) continue;
      db.execute(
          "INSERT OR IGNORE INTO notifications(event_id,user_id,task_id,message,created_at)"
              + " VALUES(?,?,?,?,?)",
          id,
          user,
          task.id(),
          task.identifier() + " · " + event.toLowerCase().replace('_', ' '),
          now);
    }
    db.execute(
        "INSERT INTO audit(actor_id,token_id,action,target,created_at) VALUES(?,?,?,?,?)",
        actor.id(),
        actor.tokenId(),
        event,
        task.identifier(),
        now);
  }
}
