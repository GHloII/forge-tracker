package dev.forge.tracker.integrations.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.forge.tracker.auth.*;
import dev.forge.tracker.common.Problem;
import dev.forge.tracker.projects.ProjectService;
import dev.forge.tracker.tasks.*;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.*;
import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.*;
import java.time.Duration;
import java.util.*;
import java.util.function.BiFunction;
import org.springframework.context.annotation.*;
import org.springframework.web.servlet.function.*;

@Configuration
public class McpConfiguration {
  private final dev.forge.tracker.common.AuditTrail audit;

  public McpConfiguration(dev.forge.tracker.common.AuditTrail audit) {
    this.audit = audit;
  }

  @Bean
  WebMvcStreamableServerTransportProvider mcpTransport(Identity identity) {
    return WebMvcStreamableServerTransportProvider.builder()
        .mcpEndpoint("/mcp")
        .keepAliveInterval(Duration.ofSeconds(30))
        .sessionIdleTimeout(Duration.ofMinutes(30))
        .maxSessions(50)
        .contextExtractor(
            request ->
                McpTransportContext.create(
                    Map.of(
                        "actor",
                        identity.actor(
                            (org.springframework.security.core.Authentication)
                                request.principal().orElseThrow(Problem::forbidden)))))
        .build();
  }

  @Bean
  RouterFunction<ServerResponse> mcpRoutes(WebMvcStreamableServerTransportProvider transport) {
    return transport.getRouterFunction();
  }

  @Bean(destroyMethod = "closeGracefully")
  McpSyncServer mcpServer(
      WebMvcStreamableServerTransportProvider transport,
      TaskService tasks,
      ProjectService projects,
      ObjectMapper mapper) {
    List<McpServerFeatures.SyncToolSpecification> tools = new ArrayList<>();
    Map<String, Object> list =
        fields(
            "project",
            integer("Project ID"),
            "q",
            string("Full-text search"),
            "page",
            integer("Zero-based page, default 0"),
            "size",
            integer("Page size 1-100, default 50"),
            "status",
            enumeration("BACKLOG", "READY", "IN_PROGRESS", "IN_REVIEW", "DONE", "CANCELLED"),
            "approval",
            enumeration("PENDING", "APPROVED", "CHANGES_REQUESTED", "REJECTED"));
    for (String name :
        List.of("list_tasks", "search_tasks", "get_my_tasks", "get_approved_tasks")) {
      tools.add(
          tool(
              name,
              "List authorized tasks with SQL pagination. get_my_tasks filters by your identity;"
                  + " get_approved_tasks returns approved revisions. Archived tasks are excluded.",
              list,
              List.of(),
              (actor, a) ->
                  tasks.list(
                      actor,
                      new TaskRepository.Filter(
                          optionalLong(a, "project"),
                          text(a, "q", null),
                          text(a, "status", null),
                          name.equals("get_approved_tasks")
                              ? "APPROVED"
                              : text(a, "approval", null),
                          null,
                          null,
                          name.equals("get_my_tasks") ? actor.id() : null,
                          null,
                          null,
                          false,
                          false,
                          "updated",
                          number(a, "page", 0),
                          number(a, "size", 50))),
              mapper));
    }
    tools.add(
        tool(
            "get_task",
            "Read task details, current version, approval revision, decisions, links and images."
                + " Use identifier such as GAME-42.",
            fields("identifier", string("Stable task identifier")),
            List.of("identifier"),
            (actor, a) -> tasks.details(actor, text(a, "identifier", null)),
            mapper));
    tools.add(
        tool(
            "get_task_comments",
            "Read a page of comments, newest first.",
            fields(
                "identifier",
                string("Task identifier"),
                "page",
                integer("Zero-based page"),
                "size",
                integer("1-100, default 30")),
            List.of("identifier"),
            (actor, a) ->
                tasks.history(
                    actor,
                    text(a, "identifier", null),
                    "comments",
                    number(a, "page", 0),
                    number(a, "size", 30)),
            mapper));
    tools.add(
        tool(
            "get_project_context",
            "Read project description, repository and participants. Omit project to discover your"
                + " authorized projects.",
            fields("project", integer("Project ID")),
            List.of(),
            (actor, a) ->
                a.containsKey("project")
                    ? projects.context(actor, requiredLong(a, "project"))
                    : projects.list(actor),
            mapper));
    Map<String, Object> taskFields =
        fields(
            "title",
            string("1-240 characters"),
            "description",
            string("Markdown requirements, max 100000 characters"),
            "type",
            enumeration("FEATURE", "BUG", "IMPROVEMENT", "RESEARCH"),
            "priority",
            enumeration("URGENT", "HIGH", "MEDIUM", "LOW", "NONE"),
            "category",
            string("Category, max 120 characters"),
            "labels",
            Map.of("type", "array", "items", Map.of("type", "string"), "maxItems", 20));
    Map<String, Object> createFields = new LinkedHashMap<>(taskFields);
    createFields.put("project", integer("Authorized project ID"));
    tools.add(
        tool(
            "create_task",
            "Propose a task. All proposals enter BACKLOG/PENDING and notify human reviewers."
                + " Approval is mandatory.",
            createFields,
            List.of("project", "title"),
            (actor, a) -> tasks.create(actor, requiredLong(a, "project"), input(a)),
            mapper));
    Map<String, Object> updateFields = new LinkedHashMap<>(taskFields);
    updateFields.putAll(
        fields(
            "identifier",
            string("Task identifier"),
            "version",
            integer("Current task version from get_task")));
    tools.add(
        tool(
            "update_task",
            "Replace editable task fields. Supply title and current version. Title, description,"
                + " type or category changes increment revision, invalidate approval and return"
                + " task to BACKLOG/PENDING. Preserve unchanged fields from get_task.",
            updateFields,
            List.of("identifier", "version", "title"),
            (actor, a) ->
                tasks.update(actor, text(a, "identifier", null), number(a, "version", 0), input(a)),
            mapper));
    tools.add(
        tool(
            "add_comment",
            "Add progress, implementation notes or reviewer feedback. Supports @username mentions.",
            fields(
                "identifier",
                string("Task identifier"),
                "body",
                string("Markdown comment, 1-20000 characters")),
            List.of("identifier", "body"),
            (actor, a) -> {
              tasks.comment(actor, text(a, "identifier", null), text(a, "body", null));
              return Map.of("ok", true);
            },
            mapper));
    tools.add(
        tool(
            "assign_task",
            "Claim an unassigned approved task for yourself, or assign with MANAGE permission."
                + " Assignee must have project write access.",
            fields(
                "identifier",
                string("Task identifier"),
                "version",
                integer("Current version"),
                "assignee",
                integer("User or agent ID; omit to claim for yourself")),
            List.of("identifier", "version"),
            (actor, a) ->
                tasks.assign(
                    actor,
                    text(a, "identifier", null),
                    number(a, "version", 0),
                    a.containsKey("assignee") ? requiredLong(a, "assignee") : actor.id()),
            mapper));
    for (String name : List.of("start_task", "update_task_status", "complete_task")) {
      Map<String, Object> properties =
          fields("identifier", string("Task identifier"), "version", integer("Current version"));
      if (name.equals("update_task_status"))
        properties.put(
            "status",
            enumeration("BACKLOG", "READY", "IN_PROGRESS", "IN_REVIEW", "DONE", "CANCELLED"));
      tools.add(
          tool(
              name,
              name.equals("complete_task")
                  ? "Submit implementation for human review (IN_REVIEW). Does not mark Done. Human"
                      + " reviewer must accept the work."
                  : "Move task through validated workflow. start_task requires READY, approval and"
                      + " assignment. DONE is restricted to human reviewers.",
              properties,
              name.equals("update_task_status")
                  ? List.of("identifier", "version", "status")
                  : List.of("identifier", "version"),
              (actor, a) ->
                  tasks.transition(
                      actor,
                      text(a, "identifier", null),
                      number(a, "version", 0),
                      name.equals("start_task")
                          ? "IN_PROGRESS"
                          : name.equals("complete_task") ? "IN_REVIEW" : text(a, "status", null)),
              mapper));
    }
    tools.add(
        tool(
            "link_pull_request",
            "Link a GitHub PR or commit to a task. A link does not change status or approval.",
            fields(
                "identifier",
                string("Task identifier"),
                "url",
                string("HTTPS GitHub pull request or commit URL"),
                "kind",
                enumeration("PR", "COMMIT")),
            List.of("identifier", "url"),
            (actor, a) -> {
              tasks.link(
                  actor, text(a, "identifier", null), text(a, "kind", "PR"), text(a, "url", null));
              return Map.of("ok", true);
            },
            mapper));
    return McpServer.sync(transport)
        .serverInfo("forge-tracker", "1.0.0")
        .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
        .tools(tools)
        .build();
  }

  private McpServerFeatures.SyncToolSpecification tool(
      String name,
      String description,
      Map<String, Object> properties,
      List<String> required,
      BiFunction<Actor, Map<String, Object>, Object> handler,
      ObjectMapper mapper) {
    var input = new McpSchema.JsonSchema("object", properties, required, false, null, null);
    var output =
        Map.<String, Object>of(
            "type",
            "object",
            "properties",
            Map.of("data", Map.of()),
            "required",
            List.of("data"),
            "additionalProperties",
            false);
    return McpServerFeatures.SyncToolSpecification.builder()
        .tool(
            McpSchema.Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(input)
                .outputSchema(output)
                .build())
        .callHandler(
            (exchange, request) -> {
              try {
                Actor actor = (Actor) exchange.transportContext().get("actor");
                if (actor == null) throw Problem.forbidden();
                audit.invocation(actor, name);
                Map<String, Object> arguments =
                    request.arguments() == null ? Map.of() : request.arguments();
                validate(arguments, properties, required);
                Object result = Map.of("data", handler.apply(actor, arguments));
                return McpSchema.CallToolResult.builder()
                    .structuredContent(result)
                    .addTextContent(mapper.writeValueAsString(result))
                    .isError(false)
                    .build();
              } catch (Problem ex) {
                return failure(ex.code, ex.getMessage());
              } catch (IllegalArgumentException ex) {
                return failure("VALIDATION", "Invalid tool arguments.");
              } catch (org.springframework.dao.DataAccessException ex) {
                return failure(
                    "CONFLICT", "Change could not be applied. Refresh task state and retry.");
              } catch (Exception ex) {
                return failure("INTERNAL", "The operation could not be completed.");
              }
            })
        .build();
  }

  private McpSchema.CallToolResult failure(String code, String message) {
    return McpSchema.CallToolResult.builder()
        .isError(true)
        .structuredContent(Map.of("error", Map.of("code", code, "message", message)))
        .addTextContent(code + ": " + message)
        .build();
  }

  @SuppressWarnings("unchecked")
  private void validate(
      Map<String, Object> args, Map<String, Object> properties, List<String> required) {
    if (!properties.keySet().containsAll(args.keySet())
        || required.stream().anyMatch(k -> !args.containsKey(k) || args.get(k) == null))
      throw Problem.bad("Missing required or unknown tool arguments.");
    for (var entry : args.entrySet()) {
      var schema = (Map<String, Object>) properties.get(entry.getKey());
      Object value = entry.getValue();
      String type = (String) schema.get("type");
      boolean valid =
          switch (type) {
            case "string" -> value instanceof String;
            case "integer" ->
                value instanceof Number n
                    && Double.isFinite(n.doubleValue())
                    && n.doubleValue() == n.longValue()
                    && n.longValue() >= 0
                    && n.longValue() <= Integer.MAX_VALUE;
            case "array" ->
                value instanceof List<?> list
                    && list.size() <= 20
                    && list.stream().allMatch(v -> v instanceof String);
            default -> false;
          };
      if (!valid || schema.containsKey("enum") && !((List<?>) schema.get("enum")).contains(value))
        throw Problem.bad("Invalid field: " + entry.getKey());
    }
  }

  private static Map<String, Object> fields(Object... entries) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < entries.length; i += 2) map.put((String) entries[i], entries[i + 1]);
    return map;
  }

  private static Map<String, Object> string(String description) {
    return Map.of("type", "string", "description", description);
  }

  private static Map<String, Object> integer(String description) {
    return Map.of("type", "integer", "description", description, "minimum", 0);
  }

  private static Map<String, Object> enumeration(String... values) {
    return Map.of("type", "string", "enum", List.of(values));
  }

  private static String text(Map<String, Object> a, String key, String fallback) {
    return a.containsKey(key) ? (String) a.get(key) : fallback;
  }

  private static int number(Map<String, Object> a, String key, int fallback) {
    return a.containsKey(key) ? ((Number) a.get(key)).intValue() : fallback;
  }

  private static Long optionalLong(Map<String, Object> a, String key) {
    return a.containsKey(key) ? requiredLong(a, key) : null;
  }

  private static long requiredLong(Map<String, Object> a, String key) {
    return ((Number) a.get(key)).longValue();
  }

  @SuppressWarnings("unchecked")
  private static TaskInput input(Map<String, Object> a) {
    return new TaskInput(
        text(a, "title", null),
        text(a, "description", ""),
        text(a, "type", "FEATURE"),
        text(a, "priority", "MEDIUM"),
        text(a, "category", ""),
        (List<String>) a.getOrDefault("labels", List.of()));
  }
}
