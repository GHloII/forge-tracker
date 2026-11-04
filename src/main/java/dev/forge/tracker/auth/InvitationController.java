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
  @GetMapping("/invitations")
  public Object list(Authentication auth) { return invitations.list(identity.actor(auth)); }
  @PostMapping("/invitations")
  public Object create(Authentication auth) { return invitations.create(identity.actor(auth)); }
  @DeleteMapping("/invitations/{id}")
  public Object revoke(Authentication auth, @PathVariable long id) {
    invitations.revoke(identity.actor(auth), id);
    return Map.of("ok", true);
  }
  public record Registration(String token, String username, String displayName, String password) {}
  @PostMapping("/register")
  public Object register(@RequestBody Registration input) {
    invitations.register(input.token(), input.username(), input.displayName(), input.password());
    return Map.of("ok", true);
  }
}
