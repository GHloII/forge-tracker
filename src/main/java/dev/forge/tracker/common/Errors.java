package dev.forge.tracker.common;

import java.util.Map;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class Errors {
  @ExceptionHandler(Problem.class)
  ResponseEntity<?> problem(Problem ex) {
    return ResponseEntity.status(ex.status)
        .body(Map.of("code", ex.code, "message", ex.getMessage()));
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    HttpMessageNotReadableException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<?> invalid(Exception ex) {
    return ResponseEntity.badRequest()
        .body(
            Map.of("code", "VALIDATION", "message", "Invalid request. Check the supplied fields."));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<?> constraint(Exception ex) {
    return ResponseEntity.status(409)
        .body(Map.of("code", "CONSTRAINT", "message", "This change conflicts with existing data."));
  }

  @ExceptionHandler(TransientDataAccessException.class)
  ResponseEntity<?> busy(Exception ex) {
    return ResponseEntity.status(409)
        .body(
            Map.of("code", "BUSY", "message", "Another change is in progress. Refresh and retry."));
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<?> large(Exception ex) {
    return ResponseEntity.status(413)
        .body(Map.of("code", "UPLOAD_SIZE", "message", "Images must be at most 5 MB."));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> internal(Exception ex) {
    org.slf4j.LoggerFactory.getLogger(Errors.class)
        .error("Request failed with {}", ex.getClass().getSimpleName());
    return ResponseEntity.status(500)
        .body(Map.of("code", "INTERNAL", "message", "The operation could not be completed."));
  }
}
