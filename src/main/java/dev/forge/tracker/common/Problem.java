package dev.forge.tracker.common;

import org.springframework.http.HttpStatus;

public class Problem extends RuntimeException {
  public final HttpStatus status;
  public final String code;

  public Problem(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public static Problem bad(String message) {
    return new Problem(HttpStatus.BAD_REQUEST, "VALIDATION", message);
  }

  public static Problem forbidden() {
    return new Problem(
        HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission for this action.");
  }

  public static Problem missing() {
    return new Problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found.");
  }

  public static Problem conflict(String message) {
    return new Problem(HttpStatus.CONFLICT, "CONFLICT", message);
  }
}
