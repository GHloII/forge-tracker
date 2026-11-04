package dev.forge.tracker.tasks;

import static dev.forge.tracker.auth.Access.Permission.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.forge.tracker.auth.*;
import dev.forge.tracker.common.Problem;
import dev.forge.tracker.notifications.Events;
import dev.forge.tracker.persistence.Database;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class TaskService {
  private final Database db;
  private final TaskRepository repository;
  private final Access access;
  private final Events events;
  private final ObjectMapper json;

  public TaskService(
      Database db, TaskRepository repository, Access access, Events events, ObjectMapper json) {
    this.db = db;
    this.repository = repository;
    this.access = access;
    this.events = events;
    this.json = json;
  }

  public Task get(Actor actor, String identifier) {
    Task task = repository.get(identifier);
    access.require(actor, task.projectId(), READ);
    return task;
  }

  public TaskRepository.Page<Task> list(Actor actor, TaskRepository.Filter filter) {
    if (filter.project() != null) access.require(actor, filter.project(), READ);
    return repository.list(actor, filter);
  }

  public TaskRepository.Page<Map<String, Object>> listForUi(
      Actor actor, TaskRepository.Filter filter) {
    var page = list(actor, filter);
    Set<Long> following = new HashSet<>();
    if (!page.items().isEmpty()) {
      var args = new ArrayList<Object>();
      args.add(actor.id());
      page.items().forEach(task -> args.add(task.id()));
      following.addAll(
          db.list(
              Long.class,
              "SELECT task_id FROM subscriptions WHERE user_id=? AND task_id IN ("
                  + String.join(",", Collections.nCopies(page.items().size(), "?"))
                  + ")",
              args.toArray()));
    }
    var items =
        page.items().stream()
            .map(
                task -> {
                  Map<String, Object> item =
                      json.convertValue(
                          task,
                          new com.fasterxml.jackson.core.type.TypeReference<
                              Map<String, Object>>() {});
                  item.put("subscribed", following.contains(task.id()));
                  return item;
                })
            .toList();
    return new TaskRepository.Page<>(items, page.total(), page.page(), page.size());
  }

  public Task create(Actor actor, long project, TaskInput input) {
    access.require(actor, project, WRITE);
    if (input == null) throw Problem.bad("Task fields are required.");
    if (db.execute("UPDATE projects SET next_number=next_number+1 WHERE id=?", project) == 0)
      throw Problem.missing();
    String identifier =
        db.one(String.class, "SELECT key||'-'||next_number FROM projects WHERE id=?", project);
    String now = Instant.now().toString();
    db.execute(
        "INSERT INTO"
            + " tasks(project_id,identifier,title,description,type,priority,status,approval,creator_id,category,labels,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,'BACKLOG','PENDING',?,?,?,?,?)",
        project,
        identifier,
        input.title(),
        input.description(),
        input.type(),
        input.priority(),
        actor.id(),
        input.category(),
        encode(input.labels()),
        now,
        now);
    Task task = repository.get(identifier);
    snapshot(task, actor);
    subscribe(actor, identifier, true);
    events.emit(actor, task, "APPROVAL_REQUESTED", "Proposal submitted for revision 1", true);
    return task;
  }

  private Task editable(Actor actor, String identifier, int version) {
    Task task = get(actor, identifier);
    access.require(actor, task.projectId(), WRITE);
    if (task.archived()) throw Problem.conflict("Restore the task before editing it.");
    if (task.version() != version)
      throw Problem.conflict("This task changed. Refresh before saving.");
    return task;
  }

  private void canEdit(Actor actor, Task task) {
    if (actor.id() == task.creatorId() || Objects.equals(actor.id(), task.assigneeId())) return;
    access.require(actor, task.projectId(), MANAGE);
  }

  private void touch(Task task) {
    if (db.execute(
            "UPDATE tasks SET version=version+1,updated_at=? WHERE id=? AND version=?",
            Instant.now().toString(),
            task.id(),
            task.version())
        != 1) throw Problem.conflict("This task changed. Refresh before saving.");
  }

  public Task update(Actor actor, String identifier, int version, TaskInput input) {
    Task task = editable(actor, identifier, version);
    canEdit(actor, task);
    if (input == null) throw Problem.bad("Task fields are required.");
    boolean significant =
        !task.title().equals(input.title())
            || !task.description().equals(input.description())
            || !task.type().equals(input.type())
            || !task.category().equals(input.category());
    touch(task);
    db.execute(
        "UPDATE tasks SET"
            + " title=?,description=?,type=?,priority=?,category=?,labels=?,revision=revision+?,approval=?,status=?"
            + " WHERE id=?",
        input.title(),
        input.description(),
        input.type(),
        input.priority(),
        input.category(),
        encode(input.labels()),
        significant ? 1 : 0,
        significant ? "PENDING" : task.approval(),
        significant ? "BACKLOG" : task.status(),
        task.id());
    if (significant) snapshot(repository.get(identifier), actor);
    events.emit(
        actor,
        repository.get(identifier),
        significant ? "APPROVAL_REQUESTED" : "TASK_UPDATED",
        significant ? "Requirements changed; prior approval invalidated." : "Properties updated.",
        significant);
    return repository.get(identifier);
  }

  public Task approve(
      Actor actor, String identifier, int version, String decision, String comment) {
    Task task = editable(actor, identifier, version);
    access.require(actor, task.projectId(), APPROVE);
    if (decision == null || !Set.of("APPROVED", "REJECTED", "CHANGES_REQUESTED").contains(decision))
      throw Problem.bad("Invalid approval decision.");
    if (!task.approval().equals("PENDING"))
      throw Problem.conflict("Only pending revisions can be reviewed.");
    comment = comment == null ? "" : comment;
    if (comment.length() > 10000) throw Problem.bad("Feedback is limited to 10000 characters.");
    touch(task);
    db.execute(
        "INSERT INTO approvals(task_id,reviewer_id,decision,revision,comment,created_at)"
            + " VALUES(?,?,?,?,?,?)",
        task.id(),
        actor.id(),
        decision,
        task.revision(),
        comment,
        Instant.now().toString());
    db.execute("UPDATE tasks SET approval=? WHERE id=?", decision, task.id());
    events.emit(
        actor, task, "APPROVAL_" + decision, comment, false, task.creatorId(), task.assigneeId());
    return repository.get(identifier);
  }

  public Task resubmit(Actor actor, String identifier, int version) {
    Task task = editable(actor, identifier, version);
    canEdit(actor, task);
    if (!task.approval().equals("CHANGES_REQUESTED"))
      throw Problem.conflict(
          "Revise rejected proposals before resubmitting. Only changes-requested proposals can be"
              + " resubmitted unchanged.");
    touch(task);
    db.execute("UPDATE tasks SET approval='PENDING',status='BACKLOG' WHERE id=?", task.id());
    events.emit(actor, task, "APPROVAL_REQUESTED", "Resubmitted for review", true);
    return repository.get(identifier);
  }

  private static final Map<String, Set<String>> TRANSITIONS =
      Map.of(
          "BACKLOG",
          Set.of("READY", "CANCELLED"),
          "READY",
          Set.of("BACKLOG", "IN_PROGRESS", "CANCELLED"),
          "IN_PROGRESS",
          Set.of("READY", "IN_REVIEW", "CANCELLED"),
          "IN_REVIEW",
          Set.of("IN_PROGRESS", "DONE", "CANCELLED"),
          "DONE",
          Set.of("BACKLOG", "READY"),
          "CANCELLED",
          Set.of("BACKLOG"));

  public Task transition(Actor actor, String identifier, int version, String status) {
    Task task = editable(actor, identifier, version);
    if (status == null || !TRANSITIONS.get(task.status()).contains(status))
      throw Problem.conflict("Cannot move " + task.status() + " to " + status + ".");
    if (Set.of("READY", "IN_PROGRESS", "IN_REVIEW", "DONE").contains(status)
        && !task.approval().equals("APPROVED"))
      throw Problem.conflict("This revision needs human approval before work can begin.");
    if (status.equals("DONE")) access.require(actor, task.projectId(), APPROVE);
    else if (Set.of("IN_PROGRESS", "IN_REVIEW").contains(status)
        && !Objects.equals(task.assigneeId(), actor.id()))
      access.require(actor, task.projectId(), MANAGE);
    else canEdit(actor, task);
    if (status.equals("IN_PROGRESS") && task.assigneeId() == null)
      throw Problem.conflict("Assign or claim the task before starting work.");
    touch(task);
    db.execute("UPDATE tasks SET status=? WHERE id=?", status, task.id());
    events.emit(
        actor,
        task,
        status.equals("IN_PROGRESS") && task.status().equals("IN_REVIEW")
            ? "ADDITIONAL_WORK_REQUESTED"
            : "STATUS_CHANGED",
        task.status() + " → " + status,
        false,
        task.assigneeId());
    return repository.get(identifier);
  }

  public Task assign(Actor actor, String identifier, int version, Long assignee) {
    Task task = editable(actor, identifier, version);
    boolean claim =
        task.assigneeId() == null
            && Objects.equals(assignee, actor.id())
            && task.approval().equals("APPROVED");
    if (!claim) access.require(actor, task.projectId(), MANAGE);
    if (assignee != null) {
      if (db.one(Long.class, "SELECT count(*) FROM users WHERE id=? AND enabled=1", assignee) == 0)
        throw Problem.bad("Assignee is unavailable.");
      String kind = db.one(String.class, "SELECT kind FROM users WHERE id=?", assignee);
      access.require(new Actor(assignee, "", kind, null, null, null), task.projectId(), WRITE);
    }
    touch(task);
    db.execute("UPDATE tasks SET assignee_id=? WHERE id=?", assignee, task.id());
    if (assignee != null)
      db.execute(
          "INSERT OR IGNORE INTO subscriptions(task_id,user_id) VALUES(?,?)", task.id(), assignee);
    events.emit(
        actor,
        task,
        "TASK_ASSIGNED",
        assignee == null ? "Unassigned" : "Assigned to " + assignee,
        false,
        assignee);
    return repository.get(identifier);
  }

  public Task archive(Actor actor, String identifier, int version, boolean archived) {
    Task task = get(actor, identifier);
    access.require(actor, task.projectId(), MANAGE);
    if (task.version() != version) throw Problem.conflict("Refresh before archiving.");
    touch(task);
    db.execute("UPDATE tasks SET archived=? WHERE id=?", archived ? 1 : 0, task.id());
    events.emit(actor, task, archived ? "TASK_ARCHIVED" : "TASK_RESTORED", "", false);
    return repository.get(identifier);
  }

  public void subscribe(Actor actor, String identifier, boolean value) {
    Task task = get(actor, identifier);
    if (value)
      db.execute(
          "INSERT OR IGNORE INTO subscriptions(task_id,user_id) VALUES(?,?)",
          task.id(),
          actor.id());
    else
      db.execute("DELETE FROM subscriptions WHERE task_id=? AND user_id=?", task.id(), actor.id());
  }

  public Map<String, Object> details(Actor actor, String identifier) {
    Task task = get(actor, identifier);
    return Map.of(
        "task",
        task,
        "subscribed",
        db.one(
                Long.class,
                "SELECT count(*) FROM subscriptions WHERE task_id=? AND user_id=?",
                task.id(),
                actor.id())
            > 0,
        "approvals",
        db.rows(
            "SELECT a.*,u.display_name AS reviewer FROM approvals a JOIN users u ON"
                + " u.id=a.reviewer_id WHERE task_id=? ORDER BY a.id DESC LIMIT 100",
            task.id()),
        "links",
        db.rows("SELECT * FROM links WHERE task_id=? ORDER BY id DESC LIMIT 100", task.id()),
        "attachments",
        db.rows(
            "SELECT * FROM attachments WHERE task_id=? ORDER BY created_at DESC LIMIT 100",
            task.id()));
  }

  public TaskRepository.Page<Map<String, Object>> history(
      Actor actor, String identifier, String kind, int page, int size) {
    Task task = get(actor, identifier);
    if (page < 0 || size < 1 || size > 100) throw Problem.bad("Invalid pagination.");
    String table = kind.equals("comments") ? "comments" : "activity";
    String author = table.equals("comments") ? "author_id" : "actor_id";
    return new TaskRepository.Page<>(
        db.rows(
            "SELECT h.*,u.display_name AS author,u.username FROM "
                + table
                + " h JOIN users u ON u.id=h."
                + author
                + " WHERE task_id=? ORDER BY h.id DESC LIMIT ? OFFSET ?",
            task.id(),
            size,
            (long) page * size),
        db.one(Long.class, "SELECT count(*) FROM " + table + " WHERE task_id=?", task.id()),
        page,
        size);
  }

  private void snapshot(Task task, Actor actor) {
    db.execute(
        "INSERT INTO"
            + " task_revisions(task_id,revision,title,description,type,category,author_id,created_at)"
            + " VALUES(?,?,?,?,?,?,?,?)",
        task.id(),
        task.revision(),
        task.title(),
        task.description(),
        task.type(),
        task.category(),
        actor.id(),
        Instant.now().toString());
  }

  public List<Map<String, Object>> revisions(Actor actor, String identifier, int page) {
    Task task = get(actor, identifier);
    if (page < 0) throw Problem.bad("Invalid page.");
    return db.rows(
        "SELECT r.revision,r.title,r.type,r.category,r.created_at,u.display_name AS author FROM"
            + " task_revisions r JOIN users u ON u.id=r.author_id WHERE task_id=? ORDER BY revision"
            + " DESC LIMIT 20 OFFSET ?",
        task.id(),
        (long) page * 20);
  }

  public Map<String, Object> revision(Actor actor, String identifier, int revision) {
    Task task = get(actor, identifier);
    return db
        .rows("SELECT * FROM task_revisions WHERE task_id=? AND revision=?", task.id(), revision)
        .stream()
        .findFirst()
        .orElseThrow(Problem::missing);
  }

  public void comment(Actor actor, String identifier, String body) {
    Task task = get(actor, identifier);
    access.require(actor, task.projectId(), WRITE);
    if (task.archived()) throw Problem.conflict("Restore the task before commenting.");
    if (body == null || body.isBlank() || body.length() > 20000)
      throw Problem.bad("Comments must contain 1-20000 characters.");
    db.execute(
        "INSERT INTO comments(task_id,author_id,body,created_at) VALUES(?,?,?,?)",
        task.id(),
        actor.id(),
        body,
        Instant.now().toString());
    events.emit(actor, task, "COMMENT_ADDED", body, false);
  }

  public void link(Actor actor, String identifier, String kind, String url) {
    Task task = get(actor, identifier);
    access.require(actor, task.projectId(), WRITE);
    canEdit(actor, task);
    if (task.archived()) throw Problem.conflict("Restore the task before adding links.");
    if (kind == null
        || !Set.of("PR", "COMMIT", "RELATED").contains(kind)
        || url == null
        || url.length() > 500) throw Problem.bad("Invalid link.");
    if (kind.equals("RELATED")) {
      get(actor, url);
      if (url.equals(identifier)) throw Problem.bad("A task cannot relate to itself.");
    } else {
      URI uri;
      try {
        uri = URI.create(url);
      } catch (IllegalArgumentException e) {
        throw Problem.bad("Invalid GitHub URL.");
      }
      String pattern =
          kind.equals("PR")
              ? "/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/pull/[0-9]+"
              : "/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/commit/[a-fA-F0-9]{7,64}";
      if (!"https".equals(uri.getScheme())
          || !"github.com".equals(uri.getHost())
          || uri.getUserInfo() != null
          || !uri.getPath().matches(pattern)
          || uri.getQuery() != null
          || uri.getFragment() != null)
        throw Problem.bad("Use a direct HTTPS GitHub pull request or commit URL.");
    }
    if (db.execute(
            "INSERT OR IGNORE INTO links(task_id,kind,url) VALUES(?,?,?)", task.id(), kind, url)
        > 0) events.emit(actor, task, "LINK_ADDED", url, false);
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException(e);
    }
  }
}
