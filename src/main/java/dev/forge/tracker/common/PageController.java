package dev.forge.tracker.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Serves SvelteKit's prerendered pages and falls back for runtime browser routes. */
@Controller
public class PageController {
  // Backend endpoints and generated assets must never fall back to the browser shell.
  public static final String PAGE_PATH =
      "/{page:^(?!api$|mcp$|actuator$|error$|_app$|assets$)[^.]+}/**";

  @GetMapping(PAGE_PATH)
  public ResponseEntity<Resource> page(HttpServletRequest request) {
    String path = request.getServletPath().replaceAll("/+$", "");
    Resource page = new ClassPathResource("static/200.html");
    if (path.matches("/[A-Za-z0-9_/-]+")) {
      Resource prerendered = new ClassPathResource("static" + path + ".html");
      if (prerendered.exists()) page = prerendered;
    }
    return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(page);
  }
}
