package dev.forge.tracker.integrations.github;

import dev.forge.tracker.auth.*;
import dev.forge.tracker.common.Problem;
import dev.forge.tracker.persistence.Database;
import dev.forge.tracker.tasks.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@Service
public class MarkdownImport {
  public record Entry(
      String title,
      String category,
      String description,
      boolean completed,
      String fingerprint,
      boolean duplicate) {}

  private final Database db;
  private final TaskService tasks;
  private final Access access;

  public MarkdownImport(Database db, TaskService tasks, Access access) {
    this.db = db;
    this.tasks = tasks;
    this.access = access;
  }

  public List<Entry> preview(Actor actor, long project, String source, String markdown) {
    access.admin(actor);
    access.require(actor, project, Access.Permission.MANAGE);
    if (source == null
        || source.isBlank()
        || source.length() > 200
        || markdown == null
        || markdown.length() > 500000)
      throw Problem.bad("Supply a source name and Markdown up to 500 KB.");
    List<Entry> entries = new ArrayList<>();
    String category = "";
    String title = null;
    StringBuilder description = new StringBuilder();
    boolean completed = false;
    boolean fenced = false;
    var pattern = Pattern.compile("^\\s*[-*+] \\[([ xX])\\]\\s+(.+)$");
    for (String line : (markdown + "\n# __end__").split("\\R", -1)) {
      if (line.stripLeading().startsWith("```") || line.stripLeading().startsWith("~~~")) {
        fenced = !fenced;
        if (title != null) description.append(line).append('\n');
        continue;
      }
      var match = pattern.matcher(line);
      boolean heading = !fenced && line.matches("^#{1,6}\\s+.*");
      if (!fenced && (match.matches() || heading)) {
        if (title != null)
          entries.add(
              new Entry(title, category, description.toString().strip(), completed, "", false));
        title = null;
        description.setLength(0);
        if (heading) category = line.replaceFirst("^#{1,6}\\s+", "").strip();
        else {
          title = match.group(2).strip();
          completed = !match.group(1).equals(" ");
        }
      } else if (title != null) description.append(line.replaceFirst("^ {2,4}", "")).append('\n');
    }
    if (entries.size() > 200) throw Problem.bad("Import at most 200 tasks at a time.");
    Map<String, Integer> occurrences = new HashMap<>();
    List<Entry> result = new ArrayList<>();
    for (Entry e : entries) {
      new TaskInput(e.title(), e.description(), "FEATURE", "MEDIUM", e.category(), List.of());
      String key =
          e.category().strip().toLowerCase(Locale.ROOT)
              + "\n"
              + e.title().strip().toLowerCase(Locale.ROOT);
      int occurrence = occurrences.merge(key, 1, Integer::sum);
      String fingerprint = AgentTokens.hash(key + "\n" + occurrence);
      boolean duplicate =
          db.one(
                  Long.class,
                  "SELECT count(*) FROM imports WHERE project_id=? AND source=? AND fingerprint=?",
                  project,
                  source,
                  fingerprint)
              > 0;
      result.add(
          new Entry(
              e.title(), e.category(), e.description(), e.completed(), fingerprint, duplicate));
    }
    return result;
  }

  @Transactional
  public Object apply(Actor actor, long project, String source, String markdown) {
    List<Entry> entries = preview(actor, project, source, markdown);
    List<String> created = new ArrayList<>();
    for (Entry e : entries) {
      if (e.duplicate()) continue;
      Task task =
          tasks.create(
              actor,
              project,
              new TaskInput(
                  e.title(), e.description(), "FEATURE", "MEDIUM", e.category(), List.of()));
      if (e.completed()) {
        task =
            tasks.approve(
                actor,
                task.identifier(),
                task.version(),
                "APPROVED",
                "Imported completed task, confirmed by workspace owner.");
        task = tasks.assign(actor, task.identifier(), task.version(), actor.id());
        for (String status : List.of("READY", "IN_PROGRESS", "IN_REVIEW", "DONE"))
          task = tasks.transition(actor, task.identifier(), task.version(), status);
      }
      db.execute(
          "INSERT INTO imports(project_id,source,fingerprint,task_id) VALUES(?,?,?,?)",
          project,
          source,
          e.fingerprint(),
          task.id());
      created.add(task.identifier());
    }
    return Map.of("created", created, "skipped", entries.size() - created.size());
  }

  @RestController
  @RequestMapping("/api/projects/{project}/import")
  public static class Controller {
    private final MarkdownImport importer;
    private final Identity identity;

    public Controller(MarkdownImport importer, Identity identity) {
      this.importer = importer;
      this.identity = identity;
    }

    public record Input(String source, String markdown) {}

    @PostMapping("/preview")
    public Object preview(
        Authentication auth, @PathVariable long project, @RequestBody Input input) {
      return importer.preview(identity.actor(auth), project, input.source(), input.markdown());
    }

    @PostMapping
    public Object apply(Authentication auth, @PathVariable long project, @RequestBody Input input) {
      return importer.apply(identity.actor(auth), project, input.source(), input.markdown());
    }
  }
}
