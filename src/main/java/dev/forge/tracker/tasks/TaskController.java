package dev.forge.tracker.tasks;

import dev.forge.tracker.auth.*;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {
  private final TaskService tasks;
  private final Identity identity;

  public TaskController(TaskService tasks, Identity identity) {
    this.tasks = tasks;
    this.identity = identity;
  }

  @GetMapping
  public Object list(
      Authentication auth,
      @RequestParam(required = false) Long project,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String approval,
      @RequestParam(required = false) String priority,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) Long assignee,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String label,
      @RequestParam(defaultValue = "false") boolean subscribed,
      @RequestParam(defaultValue = "false") boolean archived,
      @RequestParam(defaultValue = "updated") String sort,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return tasks.listForUi(
        identity.actor(auth),
        new TaskRepository.Filter(
            project,
            q,
            status,
            approval,
            priority,
            type,
            assignee,
            category,
            label,
            subscribed,
            archived,
            sort,
            page,
            size));
  }

  public record Create(long project, TaskInput task) {}

  public record Update(int version, TaskInput task) {}

  public record Decision(int version, String decision, String comment) {}

  public record Status(int version, String status) {}

  public record Assignment(int version, Long assignee) {}

  public record Archive(int version, boolean archived) {}

  public record Version(int version) {}

  public record Comment(String body) {}

  public record Link(String kind, String url) {}

  public record Subscription(boolean subscribed) {}

  @PostMapping
  public Object create(Authentication auth, @RequestBody Create input) {
    return tasks.create(identity.actor(auth), input.project(), input.task());
  }

  @GetMapping("/{id}")
  public Object get(Authentication auth, @PathVariable String id) {
    return tasks.details(identity.actor(auth), id);
  }

  @PutMapping("/{id}")
  public Object update(Authentication auth, @PathVariable String id, @RequestBody Update input) {
    return tasks.update(identity.actor(auth), id, input.version(), input.task());
  }

  @PostMapping("/{id}/approval")
  public Object approve(Authentication auth, @PathVariable String id, @RequestBody Decision input) {
    return tasks.approve(
        identity.actor(auth), id, input.version(), input.decision(), input.comment());
  }

  @PostMapping("/{id}/resubmit")
  public Object resubmit(Authentication auth, @PathVariable String id, @RequestBody Version input) {
    return tasks.resubmit(identity.actor(auth), id, input.version());
  }

  @PostMapping("/{id}/status")
  public Object status(Authentication auth, @PathVariable String id, @RequestBody Status input) {
    return tasks.transition(identity.actor(auth), id, input.version(), input.status());
  }

  @PostMapping("/{id}/assign")
  public Object assign(
      Authentication auth, @PathVariable String id, @RequestBody Assignment input) {
    return tasks.assign(identity.actor(auth), id, input.version(), input.assignee());
  }

  @PostMapping("/{id}/archive")
  public Object archive(Authentication auth, @PathVariable String id, @RequestBody Archive input) {
    return tasks.archive(identity.actor(auth), id, input.version(), input.archived());
  }

  @PostMapping("/{id}/subscription")
  public Object subscribe(
      Authentication auth, @PathVariable String id, @RequestBody Subscription input) {
    tasks.subscribe(identity.actor(auth), id, input.subscribed());
    return Map.of("ok", true);
  }

  @GetMapping("/{id}/comments")
  public Object comments(
      Authentication auth,
      @PathVariable String id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "30") int size) {
    return tasks.history(identity.actor(auth), id, "comments", page, size);
  }

  @GetMapping("/{id}/activity")
  public Object activity(
      Authentication auth,
      @PathVariable String id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "30") int size) {
    return tasks.history(identity.actor(auth), id, "activity", page, size);
  }

  @GetMapping("/{id}/revisions")
  public Object revisions(
      Authentication auth, @PathVariable String id, @RequestParam(defaultValue = "0") int page) {
    return tasks.revisions(identity.actor(auth), id, page);
  }

  @GetMapping("/{id}/revisions/{revision}")
  public Object revision(Authentication auth, @PathVariable String id, @PathVariable int revision) {
    return tasks.revision(identity.actor(auth), id, revision);
  }

  @PostMapping("/{id}/comments")
  public Object comment(Authentication auth, @PathVariable String id, @RequestBody Comment input) {
    tasks.comment(identity.actor(auth), id, input.body());
    return Map.of("ok", true);
  }

  @PostMapping("/{id}/links")
  public Object link(Authentication auth, @PathVariable String id, @RequestBody Link input) {
    tasks.link(identity.actor(auth), id, input.kind(), input.url());
    return Map.of("ok", true);
  }
}
