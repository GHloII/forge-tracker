package dev.forge.tracker.auth;

import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class InvitationController {
  private final Invitations invitations;
  private final Identity identity;
  public InvitationController(Invitations invitations, Identity identity) {
    this.invitations = invitations;
    this.identity = identity;
  }
  public record InvitationInput(long role) {}
  public record Acceptance(String token) {}

  @GetMapping("/projects/{project}/invitations")
  public Object list(Authentication auth, @PathVariable long project) {
    return invitations.list(identity.actor(auth), project);
  }
  @PostMapping("/projects/{project}/invitations")
  public Object create(Authentication auth, @PathVariable long project, @RequestBody InvitationInput input) {
    return invitations.create(identity.actor(auth), project, input.role());
  }
  @DeleteMapping("/projects/{project}/invitations/{id}")
  public Object revokeProject(Authentication auth, @PathVariable long project, @PathVariable long id) {
    invitations.revoke(identity.actor(auth), project, id);
    return Map.of("ok", true);
  }
  @DeleteMapping("/invitations/{id}")
  public Object revoke(Authentication auth, @PathVariable long id) {
    invitations.revoke(identity.actor(auth), id);
    return Map.of("ok", true);
  }
  public record Registration(String token, String username, String displayName, String password) {}
  @PostMapping("/register")
  public Object register(@RequestBody Registration input) {
    return invitations.register(input.token(), input.username(), input.displayName(), input.password());
  }
  @PostMapping("/invitations/accept")
  public Object accept(Authentication auth, @RequestBody Acceptance input) {
    return invitations.accept(identity.actor(auth), input.token());
  }
}
