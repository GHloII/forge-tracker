package dev.forge.tracker.projects;

import dev.forge.tracker.auth.Identity;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{project}")
public class ProjectRoleController {
  private final ProjectRoles roles;
  private final Identity identity;

  public ProjectRoleController(ProjectRoles roles, Identity identity) {
    this.roles = roles;
    this.identity = identity;
  }

  public record RoleInput(String name, String permission) {}
  public record Assignment(long role) {}

  @GetMapping("/roles")
  public Object list(Authentication auth, @PathVariable long project) {
    return roles.list(identity.actor(auth), project);
  }

  @PostMapping("/roles")
  public Object create(Authentication auth, @PathVariable long project, @RequestBody RoleInput input) {
    return Map.of("id", roles.create(identity.actor(auth), project, input.name(), input.permission()));
  }

  @PutMapping("/roles/{role}")
  public Object update(Authentication auth, @PathVariable long project, @PathVariable long role,
      @RequestBody RoleInput input) {
    roles.update(identity.actor(auth), project, role, input.name(), input.permission());
    return Map.of("ok", true);
  }

  @PutMapping("/members/{user}/role")
  public Object assign(Authentication auth, @PathVariable long project, @PathVariable long user,
      @RequestBody Assignment input) {
    roles.assign(identity.actor(auth), project, user, input.role());
    return Map.of("ok", true);
  }
}
