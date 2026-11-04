package dev.forge.tracker.tasks;

import dev.forge.tracker.auth.Actor;
import dev.forge.tracker.common.Problem;
import dev.forge.tracker.persistence.Database;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class TaskRepository {
  private final Database db;

  public TaskRepository(Database db) {
    this.db = db;
  }

  public Task get(String identifier) {
    return db.optional(Task.class, "SELECT * FROM tasks WHERE identifier=?", identifier)
        .orElseThrow(Problem::missing);
  }

  public record Page<T>(List<T> items, long total, int page, int size) {}

  public record Filter(
      Long project,
      String query,
      String status,
      String approval,
      String priority,
      String type,
      Long assignee,
      String category,
      String label,
      boolean subscribed,
      boolean archived,
      String sort,
      int page,
      int size) {
    public Filter {
      if (page < 0 || page > 100000 || size < 1 || size > 100)
        throw Problem.bad("Page must be nonnegative; size must be 1-100.");
      if (query != null && query.length() > 200)
        throw Problem.bad("Search is limited to 200 characters.");
    }
  }

  public Page<Task> list(Actor actor, Filter filter) {
    var args = new ArrayList<Object>();
    var sql = new StringBuilder(" FROM tasks t WHERE t.archived=?");
    args.add(filter.archived() ? 1 : 0);
    if (!actor.owner()) {
      sql.append(
          " AND EXISTS(SELECT 1 FROM members m WHERE m.project_id=t.project_id AND m.user_id=?)");
      args.add(actor.id());
    }
    if (actor.agent()) {
      sql.append(
          " AND EXISTS(SELECT 1 FROM users a JOIN users h ON h.id=a.account_id WHERE a.id=? AND"
              + " a.enabled=1 AND h.enabled=1 AND (h.kind='OWNER' OR (h.kind='DEVELOPER' AND"
              + " EXISTS(SELECT 1 FROM members hm WHERE hm.project_id=t.project_id AND"
              + " hm.user_id=h.id))))");
      args.add(actor.id());
    }
    if (actor.tokenId() != null) {
      sql.append(
          " AND EXISTS(SELECT 1 FROM tokens tok JOIN token_projects tp ON tp.token_id=tok.id WHERE"
              + " tok.id=? AND tok.user_id=? AND tok.revoked=0 AND (tok.expires_at IS NULL OR"
              + " julianday(tok.expires_at)>julianday('now')) AND tp.project_id=t.project_id)");
      args.add(actor.tokenId());
      args.add(actor.id());
    }
    if (filter.project() != null) {
      sql.append(" AND t.project_id=?");
      args.add(filter.project());
    }
    append(sql, args, "t.status", filter.status());
    append(sql, args, "t.approval", filter.approval());
    append(sql, args, "t.priority", filter.priority());
    append(sql, args, "t.type", filter.type());
    append(sql, args, "t.assignee_id", filter.assignee());
    append(sql, args, "t.category", filter.category());
    if (filter.label() != null && !filter.label().isBlank()) {
      sql.append(" AND EXISTS(SELECT 1 FROM json_each(t.labels) WHERE value=?)");
      args.add(filter.label());
    }
    if (filter.subscribed()) {
      sql.append(" AND EXISTS(SELECT 1 FROM subscriptions s WHERE s.task_id=t.id AND s.user_id=?)");
      args.add(actor.id());
    }
    if (filter.query() != null && !filter.query().isBlank()) {
      String query = filter.query().strip();
      sql.append(" AND t.id IN(SELECT rowid FROM task_search WHERE task_search MATCH ?)");
      args.add(
          Arrays.stream(query.split("\\s+"))
              .map(s -> "\"" + s.replace("\"", "\"\"") + "\"*")
              .reduce((a, b) -> a + " AND " + b)
              .orElse("\"\""));
    }
    long total = db.one(Long.class, "SELECT count(*)" + sql, args.toArray());
    String sort =
        switch (filter.sort() == null ? "updated" : filter.sort()) {
          case "created" -> "t.id DESC";
          case "title" -> "t.title COLLATE NOCASE,t.id DESC";
          case "priority" ->
              "CASE t.priority WHEN 'URGENT' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 WHEN"
                  + " 'LOW' THEN 3 ELSE 4 END,t.id DESC";
          default -> "t.updated_at DESC,t.id DESC";
        };
    args.add(filter.size());
    args.add((long) filter.page() * filter.size());
    return new Page<>(
        db.list(
            Task.class,
            "SELECT t.*"
                + sql
                + " ORDER BY CASE WHEN t.approval='REJECTED' THEN 1 ELSE 0 END,"
                + sort
                + " LIMIT ? OFFSET ?",
            args.toArray()),
        total,
        filter.page(),
        filter.size());
  }

  private void append(StringBuilder sql, List<Object> args, String field, Object value) {
    if (value != null && !value.toString().isBlank()) {
      sql.append(" AND ").append(field).append("=?");
      args.add(value);
    }
  }
}
