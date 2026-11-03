package dev.forge.tracker.auth;

public record Actor(
    long id, String username, String kind, Long tokenId, Long projectId, String tokenPermission) {
  public boolean agent() {
    return "AGENT".equals(kind);
  }

  public boolean owner() {
    return "OWNER".equals(kind) && tokenId == null;
  }
}
