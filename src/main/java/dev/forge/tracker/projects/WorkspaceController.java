package dev.forge.tracker.projects;

import dev.forge.tracker.auth.*;
import dev.forge.tracker.common.Problem;
import dev.forge.tracker.persistence.Database;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class WorkspaceController {
  private final ProjectService projects;
  private final Identity identity;
  private final AgentTokens tokens;
  private final Database db;
  private final Access access;

  public WorkspaceController(
      ProjectService projects, Identity identity, AgentTokens tokens, Database db, Access access) {
    this.projects = projects;
    this.identity = identity;
    this.tokens = tokens;
    this.db = db;
    this.access = access;
  }

  @GetMapping("/csrf")
  public Object csrf(CsrfToken token) {
    return Map.of("token", token.getToken(), "header", token.getHeaderName());
  }

  @GetMapping("/me")
  public Object me(Authentication auth) {
    return identity.actor(auth);
  }

  @GetMapping("/projects")
  public Object projects(Authentication auth) {
    return projects.list(identity.actor(auth));
  }

  @GetMapping("/projects/{id}")
  public Object context(Authentication auth, @PathVariable long id) {
    return projects.context(identity.actor(auth), id);
  }

  public record ProjectInput(String key, String name, String description, String repository) {}

  @PostMapping("/projects")
  public Object createProject(Authentication auth, @RequestBody ProjectInput input) {
    return Map.of(
        "id",
        projects.create(
            identity.actor(auth),
            input.key(),
            input.name(),
            input.description(),
            input.repository()));
  }

  @PutMapping("/projects/{id}")
  public Object updateProject(
      Authentication auth, @PathVariable long id, @RequestBody ProjectInput input) {
    projects.update(
        identity.actor(auth),
        id,
        input.key(),
        input.name(),
        input.description(),
        input.repository());
    return Map.of("ok", true);
  }

  public record Member(long user, String permission) {}

  @PostMapping("/projects/{id}/members")
  public Object member(Authentication auth, @PathVariable long id, @RequestBody Member input) {
    projects.member(identity.actor(auth), id, input.user(), input.permission());
    return Map.of("ok", true);
  }

  @GetMapping("/users")
  public Object users(Authentication auth) {
    return projects.users(identity.actor(auth));
  }

  public record UserInput(String username, String displayName, String kind, String password) {}

  @PostMapping("/users")
  public Object user(Authentication auth, @RequestBody UserInput input) {
    return Map.of(
        "id",
        projects.user(
            identity.actor(auth),
            input.username(),
            input.displayName(),
            input.kind(),
            input.password()));
  }

  public record UserUpdate(boolean enabled, String password) {}

  @PutMapping("/users/{id}")
  public Object updateUser(
      Authentication auth, @PathVariable long id, @RequestBody UserUpdate input) {
    projects.updateUser(identity.actor(auth), id, input.enabled(), input.password());
    return Map.of("ok", true);
  }

  public record Password(String current, String replacement) {}

  @PostMapping("/password")
  public Object password(Authentication auth, @RequestBody Password input) {
    projects.changePassword(identity.actor(auth), input.current(), input.replacement());
    return Map.of("ok", true);
  }

  public record TokenInput(
      long user, long project, String name, String permission, String expiresAt) {}

  @PostMapping("/tokens")
  public Object token(Authentication auth, @RequestBody TokenInput input) {
    return tokens.create(
        identity.actor(auth),
        input.user(),
        input.project(),
        input.name(),
        input.permission(),
        input.expiresAt());
  }

  @GetMapping("/tokens")
  public Object tokens(Authentication auth) {
    access.admin(identity.actor(auth));
    return db.rows(
        "SELECT"
            + " t.id,t.user_id,t.project_id,t.name,t.permission,t.expires_at,t.revoked,t.created_at,u.username"
            + " FROM tokens t JOIN users u ON u.id=t.user_id ORDER BY t.id DESC LIMIT 200");
  }

  @DeleteMapping("/tokens/{id}")
  public Object revoke(Authentication auth, @PathVariable long id) {
    tokens.revoke(identity.actor(auth), id);
    return Map.of("ok", true);
  }

  @GetMapping("/audit")
  public Object audit(Authentication auth, @RequestParam(defaultValue = "0") int page) {
    access.admin(identity.actor(auth));
    if (page < 0) throw Problem.bad("Invalid page.");
    return db.rows(
        "SELECT a.*,u.username FROM audit a LEFT JOIN users u ON u.id=a.actor_id ORDER BY a.id DESC"
            + " LIMIT 50 OFFSET ?",
        (long) page * 50);
  }
}
