package dev.forge.tracker.common;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Static hosting fallback; SvelteKit alone defines and matches browser page routes. */
@Controller
public class PageController {
  // Backend endpoints and generated assets must never fall back to the browser shell.
  public static final String PAGE_PATH =
      "/{page:^(?!api$|mcp$|actuator$|error$|_app$|assets$)[^.]+}/**";

  @GetMapping(PAGE_PATH)
  public String page() {
    return "forward:/index.html";
  }
}
