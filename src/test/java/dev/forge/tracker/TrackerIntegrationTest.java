package dev.forge.tracker;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import dev.forge.tracker.auth.*;
import dev.forge.tracker.common.Problem;
import dev.forge.tracker.integrations.github.MarkdownImport;
import dev.forge.tracker.persistence.Database;
import dev.forge.tracker.projects.ProjectService;
import dev.forge.tracker.tasks.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "tracker.bootstrap-password=test-password-12345",
      "server.servlet.session.cookie.secure=false"
    })
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class TrackerIntegrationTest {
  static final Path DATA;

  static {
    try {
      DATA = Files.createTempDirectory("forge-integration-");
    } catch (Exception ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("tracker.data-dir", () -> DATA.toString());
    registry.add(
        "spring.datasource.url",
        () ->
            "jdbc:sqlite:"
                + DATA.resolve("test.db")
                + "?journal_mode=WAL&foreign_keys=on&busy_timeout=5000&transaction_mode=IMMEDIATE");
  }

  @Autowired TaskService tasks;
  @Autowired ProjectService projects;
  @Autowired AgentTokens tokens;
  @Autowired Invitations invitations;
  @Autowired dev.forge.tracker.projects.ProjectRoles roles;
  @Autowired Access access;
  @Autowired Database db;
  @Autowired MarkdownImport importer;
  @Autowired Attachments attachments;
  @Autowired ObjectMapper json;
  @Autowired org.springframework.test.web.servlet.MockMvc mvc;
  @LocalServerPort int port;
  Actor owner, developer, other, agent;
  long project, isolated;
  String token;
  static int sequence = 0;

  @BeforeEach
  void setup() {
    int n = ++sequence;
    owner =
        new Actor(
            db.one(Long.class, "SELECT id FROM users WHERE username='owner'"),
            "owner",
            "OWNER",
            null,
            null,
            null);
    project =
        projects.create(
            owner, "P" + n, "Project " + n, "Project context", "https://github.com/team/game");
    isolated = projects.create(owner, "X" + n, "Isolated " + n, "Private", " ".strip());
    long developerId =
        projects.user(owner, "dev" + n, "Developer " + n, "DEVELOPER", "password-123456");
    long otherId = projects.user(owner, "other" + n, "Other " + n, "DEVELOPER", "password-123456");
    long agentId = projects.user(owner, "agent" + n, "Agent " + n, "AGENT", null);
    developer = new Actor(developerId, "dev" + n, "DEVELOPER", null, null, null);
    other = new Actor(otherId, "other" + n, "DEVELOPER", null, null, null);
    projects.member(owner, project, developerId, "WRITE");
    projects.member(owner, project, otherId, "WRITE");
    projects.member(owner, project, agentId, "WRITE");
    token =
        (String) tokens.create(owner, agentId, project, "Coding agent", "WRITE", null).get("token");
    agent = tokens.authenticate(token);
  }

  @Test
  void agentAccessIsCappedByLinkedHumanAccount() {
    projects.bindAgent(owner, agent.id(), developer.id());
    projects.member(owner, project, agent.id(), "MANAGE");
    db.execute("UPDATE tokens SET permission='MANAGE' WHERE id=?", agent.tokenId());
    access.require(agent, project, Access.Permission.WRITE);
    assertThatThrownBy(() -> access.require(agent, project, Access.Permission.MANAGE)).isInstanceOf(Problem.class);
    projects.member(owner, project, developer.id(), "READ");
    access.require(agent, project, Access.Permission.READ);
    assertThatThrownBy(() -> tasks.create(agent, project, input("Denied write"))).isInstanceOf(Problem.class);
    projects.member(owner, project, developer.id(), "REMOVE");
    assertThat(projects.list(agent)).isEmpty();
    assertThatThrownBy(() -> access.require(agent, project, Access.Permission.READ)).isInstanceOf(Problem.class);
    projects.member(owner, project, developer.id(), "MANAGE");
    access.require(agent, project, Access.Permission.MANAGE);
    projects.updateUser(owner, developer.id(), false, null);
    assertThatThrownBy(() -> tokens.authenticate(token)).isInstanceOf(Problem.class);
    assertThatThrownBy(() -> access.require(agent, project, Access.Permission.READ)).isInstanceOf(Problem.class);
    assertThatThrownBy(() -> projects.bindAgent(owner, agent.id(), agent.id())).isInstanceOf(Problem.class);
    assertThatThrownBy(() -> projects.bindAgent(developer, agent.id(), owner.id())).isInstanceOf(Problem.class);
    projects.bindAgent(owner, agent.id(), owner.id());
    assertThatThrownBy(() -> access.require(agent, project, Access.Permission.APPROVE)).isInstanceOf(Problem.class);
    assertThatThrownBy(() -> access.admin(agent)).isInstanceOf(Problem.class);
    db.execute("UPDATE tokens SET permission='READ' WHERE id=?", agent.tokenId());
    assertThatThrownBy(() -> access.require(agent, project, Access.Permission.WRITE)).isInstanceOf(Problem.class);
  }

  @Test
  void mcpChecksLinkedAccountOnExistingSessions() throws Exception {
    projects.bindAgent(owner, agent.id(), developer.id());
    String session = mcpSession(token);
    assertThat(call(session, "create_task", Map.of("project", project, "title", "Allowed")).path("isError").asBoolean()).isFalse();
    projects.member(owner, project, developer.id(), "READ");
    assertThat(call(session, "create_task", Map.of("project", project, "title", "Blocked")).path("isError").asBoolean()).isTrue();
    assertThat(call(session, "list_tasks", Map.of("project", project)).path("isError").asBoolean()).isFalse();
    projects.updateUser(owner, developer.id(), false, null);
    assertThat(rpc(token, session, "tools/list", Map.of(), 5).status()).isEqualTo(401);
    assertThatThrownBy(() -> projects.user(owner, "unbound", "Unbound", "AGENT", null, null)).isInstanceOf(Problem.class);
    assertThatThrownBy(() -> projects.user(owner, "nested", "Nested", "AGENT", null, agent.id())).isInstanceOf(Problem.class);
  }

  @Test
  void oneClickMcpCreatesLinkedIdentityAndUsesHumanPermissions() {
    var result = tokens.connect(developer, developer.id(), project);
    Actor connected = tokens.authenticate((String) result.get("token"));
    assertThat(db.one(Long.class, "SELECT account_id FROM users WHERE id=?", connected.id())).isEqualTo(developer.id());
    access.require(connected, project, Access.Permission.WRITE);
    assertThatThrownBy(() -> access.require(connected, project, Access.Permission.MANAGE)).isInstanceOf(Problem.class);
    var again = tokens.connect(owner, developer.id(), project);
    assertThat(tokens.authenticate((String) again.get("token")).id()).isEqualTo(connected.id());
    assertThatThrownBy(() -> tokens.connect(developer, other.id(), project)).isInstanceOf(Problem.class);
    assertThatThrownBy(() -> tokens.connect(developer, developer.id(), isolated)).isInstanceOf(Problem.class);
    assertThatThrownBy(() -> tokens.connect(agent, agent.id(), project)).isInstanceOf(Problem.class);
    assertThat(tokens.list(developer)).extracting(row -> ((Number) row.get("id")).longValue()).contains(((Number) result.get("id")).longValue());
    assertThat(tokens.list(other)).extracting(row -> ((Number) row.get("id")).longValue()).doesNotContain(((Number) result.get("id")).longValue());
    assertThatThrownBy(() -> tokens.revoke(other, ((Number) result.get("id")).longValue())).isInstanceOf(Problem.class);
    tokens.revoke(developer, ((Number) again.get("id")).longValue());
    assertThatThrownBy(() -> tokens.authenticate((String) again.get("token"))).isInstanceOf(Problem.class);
    projects.member(owner, project, developer.id(), "READ");
    assertThatThrownBy(() -> tasks.create(connected, project, input("Blocked"))).isInstanceOf(Problem.class);
    Actor reader = tokens.authenticate((String) tokens.connect(developer, developer.id(), project).get("token"));
    access.require(reader, project, Access.Permission.READ);
    assertThatThrownBy(() -> access.require(reader, project, Access.Permission.WRITE)).isInstanceOf(Problem.class);
    projects.updateUser(owner, developer.id(), false, null);
    assertThatThrownBy(() -> tokens.connect(owner, developer.id(), project)).isInstanceOf(Problem.class);
  }

  @Test
  void oneClickMcpEndpointRequiresCsrfAndDistinguishesExpiredToken() throws Exception {
    var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/mcp-connections")
        .contentType("application/json").content("{\"account\":" + owner.id() + ",\"project\":" + project + "}")
        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("owner").roles("OWNER"));
    mvc.perform(request.with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf().useInvalidToken()))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("CSRF_EXPIRED"));
    mvc.perform(request.with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.token").isString());
  }

  @Test
  void mcpProjectTagsScopeListsAndRevokeAccessWithoutNewToken() {
    Task first = tasks.create(owner, project, input("First scoped task"));
    Task second = tasks.create(owner, isolated, input("Second scoped task"));
    projects.member(owner, isolated, developer.id(), "READ");
    var connection = tokens.connect(developer, developer.id(), List.of(project, isolated));
    long id = ((Number) connection.get("id")).longValue();
    Actor scoped = tokens.authenticate((String) connection.get("token"));
    assertThat(projects.list(scoped)).hasSize(2);
    assertThat(tasks.get(scoped, first.identifier()).id()).isEqualTo(first.id());
    assertThat(tasks.get(scoped, second.identifier()).id()).isEqualTo(second.id());
    assertThatThrownBy(() -> tasks.create(scoped, isolated, input("Read only"))).isInstanceOf(Problem.class);
    var filter = new dev.forge.tracker.tasks.TaskRepository.Filter(null,null,null,null,null,null,null,null,null,false,false,"updated",0,100);
    assertThat(tasks.list(scoped, filter).total()).isEqualTo(2);
    tokens.projects(developer, id, List.of(project));
    assertThatThrownBy(() -> tasks.get(scoped, second.identifier())).isInstanceOf(Problem.class);
    assertThat(projects.list(scoped)).hasSize(1);
    assertThat(tasks.list(scoped, filter).total()).isEqualTo(1);
    assertThatThrownBy(() -> tokens.projects(other, id, List.of(isolated))).isInstanceOf(Problem.class);
    tokens.projects(developer, id, List.of(project,isolated));
    access.require(scoped, isolated, Access.Permission.READ);
    projects.member(owner, isolated, developer.id(), "REMOVE");
    assertThat(tasks.list(scoped, filter).total()).isEqualTo(1);
    assertThatThrownBy(() -> tokens.projects(developer,id,List.of(isolated))).isInstanceOf(Problem.class);
    assertThat(projects.list(scoped)).hasSize(1);
    tokens.projects(developer,id,List.of());
    assertThat(tasks.list(scoped, filter).total()).isZero();
    assertThat(projects.list(scoped)).isEmpty();
    assertThatThrownBy(() -> tokens.connect(developer,developer.id(),List.of())).isInstanceOf(Problem.class);
  }

  TaskInput input(String title) {
    return new TaskInput(
        title, "## Scope\nBuild the feature", "FEATURE", "HIGH", "Gameplay", List.of("core"));
  }

  long defaultRole(String permission) {
    return db.one(Long.class, "SELECT id FROM project_roles WHERE project_id=? AND builtin=1 AND permission=?", project, permission);
  }

  @Test
  void invitationsAreRestrictedSingleUseAndTransactional() {
    assertThatThrownBy(() -> invitations.create(agent, project, defaultRole("WRITE"))).isInstanceOf(Problem.class);
    assertThatThrownBy(() -> invitations.create(developer, project, defaultRole("WRITE"))).isInstanceOf(Problem.class);
    @SuppressWarnings("unchecked")
    var invite = (Map<String, Object>) invitations.create(owner, project, defaultRole("WRITE"));
    String raw = (String) invite.get("token");
    long id = ((Number) invite.get("id")).longValue();
    assertThat(db.one(String.class, "SELECT hash FROM invitations WHERE id=?", id)).isNotEqualTo(raw);
    String name = "invited" + sequence;
    assertThatThrownBy(() -> invitations.register(raw, name, "New developer", "short"))
        .isInstanceOf(Problem.class);
    assertThatThrownBy(() -> invitations.register(raw, developer.username(), "Duplicate", "password-123456"))
        .isInstanceOf(Problem.class);
    invitations.register(raw, name, "New developer", "password-123456");
    long user = db.one(Long.class, "SELECT id FROM users WHERE username=?", name);
    assertThat(db.one(String.class, "SELECT kind FROM users WHERE id=?", user)).isEqualTo("DEVELOPER");
    assertThat(db.one(Long.class, "SELECT count(*) FROM members WHERE user_id=?", user)).isEqualTo(1L);
    assertThat(db.one(Long.class, "SELECT used_by FROM invitations WHERE id=?", id)).isEqualTo(user);
    assertThatThrownBy(() -> invitations.register(raw, name + "again", "Replay", "password-123456"))
        .isInstanceOf(Problem.class);
    assertThat(db.one(Long.class, "SELECT count(*) FROM users WHERE username=?", name + "again")).isZero();
  }

  @Test
  @SuppressWarnings("unchecked")
  void revokedExpiredAndDisabledIssuerInvitationsCannotRegister() {
    var revoked = (Map<String, Object>) invitations.create(owner, project, defaultRole("WRITE"));
    invitations.revoke(owner, ((Number) revoked.get("id")).longValue());
    assertThatThrownBy(() -> invitations.register((String) revoked.get("token"), "revoked", "Revoked", "password-123456"))
        .isInstanceOf(Problem.class);
    var expired = (Map<String, Object>) invitations.create(owner, project, defaultRole("WRITE"));
    db.execute("UPDATE invitations SET expires_at='2000-01-01T00:00:00Z' WHERE id=?", expired.get("id"));
    assertThatThrownBy(() -> invitations.register((String) expired.get("token"), "expired", "Expired", "password-123456"))
        .isInstanceOf(Problem.class);
    var disabled = (Map<String, Object>) invitations.create(owner, project, defaultRole("WRITE"));
    try {
      db.execute("UPDATE users SET enabled=0 WHERE id=?", owner.id());
      assertThatThrownBy(() -> invitations.register((String) disabled.get("token"), "disabled", "Disabled", "password-123456"))
          .isInstanceOf(Problem.class);
    } finally {
      db.execute("UPDATE users SET enabled=1 WHERE id=?", owner.id());
    }
  }

  Task approvedAssigned(Actor assignee) {
    Task task = tasks.create(developer, project, input("A feature"));
    task = tasks.approve(owner, task.identifier(), task.version(), "APPROVED", "Looks good");
    return tasks.assign(owner, task.identifier(), task.version(), assignee.id());
  }

  @Test
  void customProjectRolesApplyToMembersAndRemainProjectScoped() {
    assertThat(roles.list(owner, project)).hasSize(3);
    assertThatThrownBy(() -> roles.create(developer, project, "Lead", "MANAGE"))
        .isInstanceOf(Problem.class);
    assertThatThrownBy(() -> roles.create(agent, project, "Lead", "MANAGE"))
        .isInstanceOf(Problem.class);
    long role = roles.create(owner, project, "Designer", "READ");
    assertThatThrownBy(() -> roles.create(owner, project, "designer", "WRITE"))
        .isInstanceOf(Problem.class);
    assertThatThrownBy(() -> roles.create(owner, project, "Administrator", "ADMIN"))
        .isInstanceOf(Problem.class);
    assertThatThrownBy(() -> roles.assign(owner, isolated, developer.id(), role))
        .isInstanceOf(Problem.class);
    assertThatThrownBy(() -> invitations.create(owner, isolated, role))
        .isInstanceOf(Problem.class);
    roles.assign(owner, project, developer.id(), role);
    access.require(developer, project, Access.Permission.READ);
    assertThatThrownBy(() -> tasks.create(developer, project, input("Blocked")))
        .isInstanceOf(Problem.class);
    roles.update(owner, project, role, "Lead designer", "MANAGE");
    access.require(developer, project, Access.Permission.MANAGE);
    tasks.create(developer, project, input("Allowed"));
    assertThatThrownBy(() -> access.require(developer, project, Access.Permission.APPROVE))
        .isInstanceOf(Problem.class);
    roles.assign(owner, project, agent.id(), role);
    access.require(agent, project, Access.Permission.WRITE);
    roles.update(owner, project, role, "Designer", "READ");
    assertThatThrownBy(() -> access.require(agent, project, Access.Permission.WRITE))
        .isInstanceOf(Problem.class);
    assertThatThrownBy(() -> roles.create(agent, project, "Escalation", "WRITE"))
        .isInstanceOf(Problem.class);
    assertThatThrownBy(() -> roles.update(owner, project, defaultRole("READ"), "Changed", "WRITE"))
        .isInstanceOf(Problem.class);
  }

  @Test
  void projectInvitationsJoinExistingAccountsAndKeepTheirCurrentRole() {
    long role = roles.create(owner, project, "Tester", "READ");
    var first = invitations.create(owner, project, role);
    var result = invitations.accept(other, (String) first.get("token"));
    assertThat(((Number) result.get("project_id")).longValue()).isEqualTo(project);
    assertThat(db.one(String.class, "SELECT permission FROM members WHERE project_id=? AND user_id=?", project, other.id()))
        .isEqualTo("WRITE");
    assertThatThrownBy(() -> invitations.accept(agent, (String) first.get("token")))
        .isInstanceOf(Problem.class);
    long newcomer = projects.user(owner, "newcomer" + sequence, "Newcomer", "DEVELOPER", "password-123456");
    var second = invitations.create(owner, project, role);
    roles.update(owner, project, role, "Tester", "WRITE");
    invitations.accept(new Actor(newcomer, "", "DEVELOPER", null, null, null), (String) second.get("token"));
    assertThat(db.one(String.class, "SELECT permission FROM members WHERE project_id=? AND user_id=?", project, newcomer))
        .isEqualTo("WRITE");
    assertThat(db.one(Long.class, "SELECT count(*) FROM members WHERE project_id=? AND user_id=?", isolated, newcomer))
        .isZero();
    assertThatThrownBy(() -> invitations.revoke(owner, isolated, ((Number) first.get("id")).longValue()))
        .isInstanceOf(Problem.class);
    assertThat((List<?>) invitations.list(owner, isolated)).isEmpty();
  }

  @Test
  void legacyInvitationRemainsRedeemableWithoutGrantingProjectAccess() {
    String raw = "a".repeat(43);
    db.execute("INSERT INTO invitations(hash,creator_id,created_at,expires_at) VALUES(?,?,?,?)",
        AgentTokens.hash(raw), owner.id(), Instant.now().toString(), Instant.now().plusSeconds(3600).toString());
    String username = "legacy" + sequence;
    invitations.register(raw, username, "Legacy", "password-123456");
    assertThat(db.one(Long.class, "SELECT count(*) FROM members WHERE user_id=(SELECT id FROM users WHERE username=?)", username)).isZero();
  }

  @Test
  void approvalAndWorkflowInvariants() {
    Task pending = tasks.create(agent, project, input("Agent proposal"));
    assertThat(pending.approval()).isEqualTo("PENDING");
    assertThat(pending.status()).isEqualTo("BACKLOG");
    assertThatThrownBy(
            () -> tasks.transition(agent, pending.identifier(), pending.version(), "READY"))
        .isInstanceOf(Problem.class)
        .hasMessageContaining("human approval");
    assertThatThrownBy(
            () -> tasks.approve(agent, pending.identifier(), pending.version(), "APPROVED", ""))
        .isInstanceOf(Problem.class);
    assertThatThrownBy(
            () -> tasks.approve(developer, pending.identifier(), pending.version(), "REJECTED", ""))
        .isInstanceOf(Problem.class);
    Task task =
        tasks.approve(owner, pending.identifier(), pending.version(), "APPROVED", "Proceed");
    task = tasks.assign(agent, task.identifier(), task.version(), agent.id());
    task = tasks.transition(agent, task.identifier(), task.version(), "READY");
    task = tasks.transition(agent, task.identifier(), task.version(), "IN_PROGRESS");
    tasks.link(agent, task.identifier(), "PR", "https://github.com/team/game/pull/42");
    assertThat(tasks.get(agent, task.identifier()).status()).isEqualTo("IN_PROGRESS");
    task = tasks.transition(agent, task.identifier(), task.version(), "IN_REVIEW");
    Task review = task;
    assertThatThrownBy(() -> tasks.transition(agent, review.identifier(), review.version(), "DONE"))
        .isInstanceOf(Problem.class);
    task = tasks.transition(owner, task.identifier(), task.version(), "IN_PROGRESS");
    assertThat(task.status()).isEqualTo("IN_PROGRESS");
    task = tasks.transition(agent, task.identifier(), task.version(), "IN_REVIEW");
    task = tasks.transition(owner, task.identifier(), task.version(), "DONE");
    assertThat(task.status()).isEqualTo("DONE");
    assertThat(tasks.transition(owner, task.identifier(), task.version(), "READY").status())
        .isEqualTo("READY");
  }

  @Test
  void significantChangesInvalidateApprovalButLabelsDoNot() {
    Task task = approvedAssigned(developer);
    int revision = task.revision();
    task =
        tasks.update(
            developer,
            task.identifier(),
            task.version(),
            new TaskInput(
                task.title(),
                task.description(),
                task.type(),
                "LOW",
                task.category(),
                List.of("new-label")));
    assertThat(task.approval()).isEqualTo("APPROVED");
    assertThat(task.revision()).isEqualTo(revision);
    task = tasks.transition(developer, task.identifier(), task.version(), "READY");
    task = tasks.transition(developer, task.identifier(), task.version(), "IN_PROGRESS");
    task = tasks.update(developer, task.identifier(), task.version(), input("Changed scope"));
    assertThat(task.approval()).isEqualTo("PENDING");
    assertThat(task.status()).isEqualTo("BACKLOG");
    assertThat(task.revision()).isEqualTo(revision + 1);
    assertThat(db.one(Long.class, "SELECT count(*) FROM approvals WHERE task_id=?", task.id()))
        .isEqualTo(1);
    assertThat(tasks.revision(developer, task.identifier(), revision).get("title"))
        .isEqualTo("A feature");
    assertThat(tasks.revision(developer, task.identifier(), revision + 1).get("title"))
        .isEqualTo("Changed scope");
  }

  @Test
  void directTransitionsAndDoneSortAcrossPages() {
    Task task = tasks.create(owner, project, input("Direct move"));
    task = tasks.approve(owner, task.identifier(), task.version(), "APPROVED", "");
    task = tasks.transition(owner, task.identifier(), task.version(), "IN_PROGRESS");
    task = tasks.transition(owner, task.identifier(), task.version(), "DONE");
    Task active = tasks.create(owner, project, input("Active"));
    for (String sort : List.of("updated", "created", "title", "priority")) {
      var first = new TaskRepository.Filter(project, null, null, null, null, null, null,
          null, null, false, false, sort, 0, 1);
      var last = new TaskRepository.Filter(project, null, null, null, null, null, null,
          null, null, false, false, sort, 1, 1);
      assertThat(tasks.listForUi(owner, first).items().getFirst().get("identifier")).isEqualTo(active.identifier());
      assertThat(tasks.listForUi(owner, last).items().getFirst().get("identifier")).isEqualTo(task.identifier());
    }
    assertThat(tasks.transition(owner, task.identifier(), task.version(), "IN_REVIEW").status()).isEqualTo("IN_REVIEW");
  }

  @Test
  void rejectedTasksSortLastBeforePagination() {
    Task active = tasks.create(developer, project, input("Z active"));
    Task rejected = tasks.create(developer, project, input("A rejected"));
    tasks.approve(owner, rejected.identifier(), rejected.version(), "REJECTED", "Out of scope");
    for (String sort : List.of("updated", "created", "title", "priority")) {
      var filter = new TaskRepository.Filter(project, null, null, null, null, null, null,
          null, null, false, false, sort, 0, 1);
      assertThat(tasks.listForUi(owner, filter).items().getFirst().get("identifier"))
          .isEqualTo(active.identifier());
      var last = new TaskRepository.Filter(project, null, null, null, null, null, null,
          null, null, false, false, sort, 1, 1);
      assertThat(tasks.listForUi(owner, last).items().getFirst().get("identifier"))
          .isEqualTo(rejected.identifier());
    }
  }

  @Test
  void rejectedTaskMustBeRevisedAndChangesRequestedCanBeResubmitted() {
    Task task = tasks.create(developer, project, input("Rejected"));
    task = tasks.approve(owner, task.identifier(), task.version(), "REJECTED", "Out of scope");
    Task rejected = task;
    assertThatThrownBy(() -> tasks.resubmit(developer, rejected.identifier(), rejected.version()))
        .isInstanceOf(Problem.class);
    assertThatThrownBy(
            () -> tasks.transition(developer, rejected.identifier(), rejected.version(), "READY"))
        .isInstanceOf(Problem.class);
    task = tasks.update(developer, task.identifier(), task.version(), input("Revised scope"));
    assertThat(task.approval()).isEqualTo("PENDING");
    task = tasks.approve(owner, task.identifier(), task.version(), "CHANGES_REQUESTED", "Clarify");
    assertThat(tasks.resubmit(developer, task.identifier(), task.version()).approval())
        .isEqualTo("PENDING");
  }

  @Test
  void subscriptionsMentionsAssignmentsAndNotificationsAreDeduplicated() {
    Task task = tasks.create(developer, project, input("Notifications"));
    assertThat(tasks.details(developer, task.identifier()).get("subscribed")).isEqualTo(true);
    tasks.subscribe(other, task.identifier(), true);
    tasks.subscribe(other, task.identifier(), true);
    tasks.comment(
        developer, task.identifier(), "Hello @" + other.username() + " and @" + other.username());
    assertThat(
            db.one(
                Long.class,
                "SELECT count(*) FROM notifications WHERE user_id=? AND task_id=?",
                other.id(),
                task.id()))
        .isEqualTo(1);
    tasks.subscribe(other, task.identifier(), false);
    tasks.comment(developer, task.identifier(), "No mention");
    assertThat(
            db.one(
                Long.class,
                "SELECT count(*) FROM notifications WHERE user_id=? AND task_id=?",
                other.id(),
                task.id()))
        .isEqualTo(1);
    tasks.assign(owner, task.identifier(), task.version(), other.id());
    assertThat(
            db.one(
                Long.class,
                "SELECT count(*) FROM notifications WHERE user_id=? AND task_id=?",
                other.id(),
                task.id()))
        .isEqualTo(2);
    assertThat(
            db.one(
                Long.class,
                "SELECT count(*) FROM notifications WHERE user_id=? AND task_id=?",
                owner.id(),
                task.id()))
        .isGreaterThan(0);
  }

  @Test
  void permissionsTokenScopeExpirationAndRevocation() {
    assertThatThrownBy(
            () ->
                tokens.create(
                    owner, agent.id(), project, "Invalid expiration", "WRITE", "not-a-date"))
        .isInstanceOf(Problem.class)
        .hasMessageContaining("ISO-8601");
    Task privateTask = tasks.create(owner, isolated, input("Private"));
    assertThatThrownBy(() -> tasks.get(agent, privateTask.identifier()))
        .isInstanceOf(Problem.class);
    assertThatThrownBy(() -> tasks.create(agent, isolated, input("Escape")))
        .isInstanceOf(Problem.class);
    assertThatThrownBy(
            () -> projects.user(agent, "intruder", "Intruder", "OWNER", "password-12345"))
        .isInstanceOf(Problem.class);
    String readToken =
        (String) tokens.create(owner, agent.id(), project, "Read only", "READ", null).get("token");
    Actor reader = tokens.authenticate(readToken);
    assertThatThrownBy(() -> tasks.create(reader, project, input("Denied")))
        .isInstanceOf(Problem.class);
    db.execute(
        "UPDATE tokens SET expires_at=? WHERE hash=?",
        Instant.now().minusSeconds(10).toString(),
        AgentTokens.hash(readToken));
    assertThatThrownBy(() -> tokens.authenticate(readToken)).isInstanceOf(Problem.class);
    tokens.revoke(owner, agent.tokenId());
    assertThatThrownBy(() -> tokens.authenticate(token)).isInstanceOf(Problem.class);
    assertThatThrownBy(() -> tasks.create(agent, project, input("Revoked in flight")))
        .isInstanceOf(Problem.class);
    assertThat(db.one(String.class, "SELECT hash FROM tokens WHERE id=?", agent.tokenId()))
        .isNotEqualTo(token);
  }

  @Test
  void concurrentUpdatesCannotSilentlyOverwrite() throws Exception {
    Task task = tasks.create(developer, project, input("Original"));
    try (var pool = Executors.newFixedThreadPool(2)) {
      var gate = new CountDownLatch(1);
      var calls = new ArrayList<Future<Boolean>>();
      for (String title : List.of("First writer", "Second writer"))
        calls.add(
            pool.submit(
                () -> {
                  gate.await();
                  try {
                    tasks.update(developer, task.identifier(), task.version(), input(title));
                    return true;
                  } catch (Problem ex) {
                    assertThat(ex.code).isEqualTo("CONFLICT");
                    return false;
                  }
                }));
      gate.countDown();
      int winners = 0;
      for (var call : calls) if (call.get(10, TimeUnit.SECONDS)) winners++;
      assertThat(winners).isEqualTo(1);
      assertThat(tasks.get(developer, task.identifier()).version()).isEqualTo(2);
    }
  }

  @Test
  void identifiersAreAtomicAndArchiveDoesNotReuseThem() throws Exception {
    try (var pool = Executors.newFixedThreadPool(4)) {
      var calls = new ArrayList<Future<Task>>();
      for (int i = 0; i < 8; i++)
        calls.add(pool.submit(() -> tasks.create(developer, project, input("Concurrent task"))));
      Set<String> ids = new HashSet<>();
      for (var call : calls) ids.add(call.get(10, TimeUnit.SECONDS).identifier());
      assertThat(ids).hasSize(8);
    }
    Task task = tasks.create(developer, project, input("Archive"));
    Task archived = tasks.archive(owner, task.identifier(), task.version(), true);
    assertThat(tasks.get(owner, task.identifier()).identifier()).isEqualTo(task.identifier());
    Task next = tasks.create(developer, project, input("Next"));
    assertThat(next.identifier()).isNotEqualTo(task.identifier());
    assertThat(tasks.archive(owner, task.identifier(), archived.version(), false).archived())
        .isFalse();
  }

  @Test
  void projectManagersCanAssignOthersWithoutGainingApprovalOrCrossProjectAccess() throws Exception {
    long leadRole = roles.create(owner, project, "Team lead", "MANAGE");
    roles.assign(owner, project, developer.id(), leadRole);
    Task proposal = tasks.create(other, project, input("Managed assignment"));
    String response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/tasks/" + proposal.identifier() + "/assign")
        .contentType("application/json")
        .content(json.writeValueAsString(Map.of("version", proposal.version(), "assignee", other.id())))
        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(developer.username()))
        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
        .andReturn().getResponse().getContentAsString();
    Task assigned = json.readValue(response, Task.class);
    assertThat(assigned.assigneeId()).isEqualTo(other.id());
    assertThat(assigned.approval()).isEqualTo("PENDING");
    assertThatThrownBy(() -> tasks.assign(developer, proposal.identifier(), proposal.version(), agent.id())).isInstanceOf(Problem.class);
    Task reassigned = tasks.assign(developer, proposal.identifier(), assigned.version(), agent.id());
    Task unassigned = tasks.assign(developer, proposal.identifier(), reassigned.version(), null);
    assertThat(unassigned.assigneeId()).isNull();
    assertThatThrownBy(() -> tasks.assign(other, proposal.identifier(), unassigned.version(), developer.id())).isInstanceOf(Problem.class);
    assertThatThrownBy(() -> tasks.approve(developer, proposal.identifier(), unassigned.version(), "APPROVED", "")).isInstanceOf(Problem.class);
    Task privateTask = tasks.create(owner, isolated, input("Other project"));
    assertThatThrownBy(() -> tasks.assign(developer, privateTask.identifier(), privateTask.version(), other.id())).isInstanceOf(Problem.class);
    roles.update(owner, project, leadRole, "Team lead", "READ");
    assertThatThrownBy(() -> tasks.assign(developer, proposal.identifier(), unassigned.version(), other.id())).isInstanceOf(Problem.class);
  }

  @Test
  void issueListIncludesActorSpecificSubscriptionsAndBothPeople() throws Exception {
    Task proposal = tasks.create(developer, project, input("List review proposal"));
    tasks.create(owner, isolated, input("Private proposal"));
    var filter = new TaskRepository.Filter(project, null, null, null, null, null, null,
        null, null, false, false, "updated", 0, 1);
    var authorPage = tasks.listForUi(developer, filter);
    assertThat(authorPage.total()).isEqualTo(1);
    assertThat(authorPage.items().getFirst()).containsEntry("subscribed", true)
        .containsEntry("creatorId", developer.id()).containsEntry("assigneeId", null);
    assertThat(tasks.listForUi(other, filter).items().getFirst()).containsEntry("subscribed", false);
    tasks.subscribe(other, proposal.identifier(), true);
    assertThat(tasks.listForUi(other, filter).items().getFirst()).containsEntry("subscribed", true);
    tasks.subscribe(other, proposal.identifier(), false);
    assertThat(tasks.listForUi(other, filter).items().getFirst()).containsEntry("subscribed", false);
    assertThat(tasks.listForUi(developer, filter).items().getFirst()).containsEntry("subscribed", true);
    assertThat(tasks.listForUi(other, new TaskRepository.Filter(null, null, null, null, null,
        null, null, null, null, false, false, "updated", 0, 50)).items())
        .allSatisfy(item -> assertThat(item).containsEntry("projectId", project));
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/tasks")
        .param("project", String.valueOf(project))
        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(developer.username())))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].subscribed").value(true))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].creatorId").value(developer.id()));
    Task reviewed = tasks.approve(owner, proposal.identifier(), proposal.version(), "CHANGES_REQUESTED", "Clarify the scope");
    assertThatThrownBy(() -> tasks.approve(owner, proposal.identifier(), proposal.version(), "APPROVED", ""))
        .isInstanceOf(Problem.class);
    assertThat(reviewed.approval()).isEqualTo("CHANGES_REQUESTED");
  }

  @Test
  void sqliteConstraintsMigrationsSearchAndPagination() {
    assertThat(db.one(String.class, "PRAGMA journal_mode")).isEqualToIgnoringCase("wal");
    assertThat(db.one(Integer.class, "PRAGMA foreign_keys")).isEqualTo(1);
    assertThat(db.one(Long.class, "SELECT count(*) FROM flyway_schema_history WHERE success=1"))
        .isGreaterThan(0);
    Task first = tasks.create(developer, project, input("Unique hydrology waterfall"));
    tasks.create(developer, project, input("Another task"));
    var page =
        tasks.list(
            developer,
            new TaskRepository.Filter(
                project,
                "hydrology",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                "updated",
                0,
                1));
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.items().getFirst().identifier()).isEqualTo(first.identifier());
    var all =
        tasks.list(
            developer,
            new TaskRepository.Filter(
                project, null, null, null, null, null, null, null, null, false, false, "updated", 0,
                1));
    assertThat(all.total()).isEqualTo(2);
    assertThat(all.items()).hasSize(1);
    assertThatThrownBy(
            () ->
                db.execute(
                    "INSERT INTO subscriptions(task_id,user_id) VALUES(-1,?)", developer.id()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void markdownImportPreservesContentCompletionAndIsIdempotent() {
    String markdown =
        "# Gameplay\n"
            + "- [ ] Build a bridge\n"
            + "  A **stable** crossing.\n"
            + "- [x] Fix water\n"
            + "# UI\n"
            + "- [ ] Add shortcuts\n";
    var preview = importer.preview(owner, project, "TODO.md", markdown);
    assertThat(preview).hasSize(3);
    assertThat(preview.getFirst().description()).contains("**stable**");
    importer.apply(owner, project, "TODO.md", markdown);
    importer.apply(owner, project, "TODO.md", markdown);
    assertThat(db.one(Long.class, "SELECT count(*) FROM tasks WHERE project_id=?", project))
        .isEqualTo(3);
    assertThat(
            db.one(
                String.class,
                "SELECT status FROM tasks WHERE project_id=? AND title='Fix water'",
                project))
        .isEqualTo("DONE");
    assertThat(importer.preview(owner, project, "TODO.md", markdown))
        .allMatch(MarkdownImport.Entry::duplicate);
    assertThatThrownBy(() -> importer.apply(agent, project, "TODO.md", markdown))
        .isInstanceOf(Problem.class);
  }

  @Test
  void attachmentsValidateImageContentsAndEnforceProjectAccess() throws Exception {
    Task task = tasks.create(developer, project, input("Images"));
    byte[] png =
        Base64.getDecoder()
            .decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aX1sAAAAASUVORK5CYII=");
    var file =
        new org.springframework.mock.web.MockMultipartFile(
            "file", "../unsafe.png", "image/png", png);
    @SuppressWarnings("unchecked")
    var uploaded = (Map<String, Object>) attachments.upload(developer, task.identifier(), file);
    String id = (String) uploaded.get("id");
    assertThat(attachments.download(developer, id).getStatusCode().value()).isEqualTo(200);
    long strangerId =
        projects.user(owner, "stranger" + sequence, "Stranger", "DEVELOPER", "password-123456");
    Actor stranger = new Actor(strangerId, "stranger" + sequence, "DEVELOPER", null, null, null);
    assertThatThrownBy(() -> attachments.download(stranger, id)).isInstanceOf(Problem.class);
    assertThatThrownBy(() -> attachments.download(owner, "../../tracker.db"))
        .isInstanceOf(Problem.class);
    assertThatThrownBy(
            () ->
                attachments.upload(
                    developer,
                    task.identifier(),
                    new org.springframework.mock.web.MockMultipartFile(
                        "file", "x.svg", "image/png", "<svg onload='alert(1)'/>".getBytes())))
        .isInstanceOf(Problem.class);
    assertThat(Files.isRegularFile(DATA.resolve("attachments").resolve(id))).isTrue();
    assertThat(db.one(String.class, "SELECT filename FROM attachments WHERE id=?", id))
        .doesNotContain("/");
    assertThatThrownBy(
            () ->
                attachments.upload(
                    developer,
                    task.identifier(),
                    new org.springframework.mock.web.MockMultipartFile(
                        "file", "broken.png", "image/png", Arrays.copyOf(png, 40))))
        .isInstanceOf(Problem.class);
  }

  record McpResponse(int status, JsonNode body, String session) {}

  McpResponse rpc(String credential, String session, String method, Object params, int id)
      throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("Authorization", "Bearer " + credential);
    if (session != null)
      builder.header("Mcp-Session-Id", session).header("MCP-Protocol-Version", "2025-06-18");
    var response =
        HttpClient.newHttpClient()
            .send(
                builder
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            json.writeValueAsString(
                                Map.of(
                                    "jsonrpc", "2.0", "id", id, "method", method, "params",
                                    params))))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    String body = response.body();
    if (body.startsWith("event:") || body.startsWith("id:") || body.startsWith("data:"))
      body =
          body.lines()
              .filter(l -> l.startsWith("data:"))
              .map(l -> l.substring(5).strip())
              .reduce((a, b) -> a + "\n" + b)
              .orElse("{}");
    return new McpResponse(
        response.statusCode(),
        json.readTree(body.isBlank() ? "{}" : body),
        response.headers().firstValue("Mcp-Session-Id").orElse(session));
  }

  String mcpSession(String credential) throws Exception {
    var init =
        rpc(
            credential,
            null,
            "initialize",
            Map.of(
                "protocolVersion",
                "2025-06-18",
                "capabilities",
                Map.of(),
                "clientInfo",
                Map.of("name", "integration-test", "version", "1")),
            1);
    assertThat(init.status()).isEqualTo(200);
    assertThat(init.session()).isNotBlank();
    return init.session();
  }

  JsonNode call(String session, String name, Map<String, Object> args) throws Exception {
    var response = rpc(token, session, "tools/call", Map.of("name", name, "arguments", args), 4);
    assertThat(response.status()).isEqualTo(200);
    return response.body().path("result");
  }

  @Test
  void mcpAuthenticatesValidatesAndSharesApprovalRulesWithRestServices() throws Exception {
    assertThat(rpc("invalid", null, "tools/list", Map.of(), 1).status()).isEqualTo(401);
    String session = mcpSession(token);
    var toolsList =
        rpc(token, session, "tools/list", Map.of(), 2).body().path("result").path("tools");
    assertThat(toolsList.size()).isEqualTo(15);
    assertThat(call(session, "create_task", Map.of("project", project)).path("isError").asBoolean())
        .isTrue();
    assertThat(
            call(session, "create_task", Map.of("project", isolated, "title", "Escape"))
                .path("isError")
                .asBoolean())
        .isTrue();
    JsonNode created =
        call(session, "create_task", Map.of("project", project, "title", "MCP proposal"))
            .path("structuredContent")
            .path("data");
    String id = created.path("identifier").asText();
    assertThat(id).isNotBlank();
    assertThat(
            call(session, "get_task", Map.of("identifier", id))
                .path("structuredContent")
                .path("data")
                .path("task")
                .path("approval")
                .asText())
        .isEqualTo("PENDING");
    assertThat(
            call(session, "list_tasks", Map.of("project", project, "size", 1))
                .path("structuredContent")
                .path("data")
                .path("total")
                .asLong())
        .isEqualTo(1);
    assertThat(
            call(
                    session,
                    "update_task_status",
                    Map.of("identifier", id, "version", 1, "status", "READY"))
                .path("isError")
                .asBoolean())
        .isTrue();
    assertThat(
            call(
                    session,
                    "update_task",
                    Map.of(
                        "identifier",
                        id,
                        "version",
                        1,
                        "title",
                        "Revised MCP proposal",
                        "description",
                        "Clarified scope"))
                .path("isError")
                .asBoolean())
        .isFalse();
    Task task = tasks.get(owner, id);
    assertThat(task.revision()).isEqualTo(2);
    task = tasks.approve(owner, id, task.version(), "APPROVED", "Approved through shared service");
    assertThat(
            call(session, "assign_task", Map.of("identifier", id, "version", task.version()))
                .path("isError")
                .asBoolean())
        .isFalse();
    task = tasks.get(owner, id);
    assertThat(
            call(
                    session,
                    "update_task_status",
                    Map.of("identifier", id, "version", task.version(), "status", "READY"))
                .path("isError")
                .asBoolean())
        .isFalse();
    task = tasks.get(owner, id);
    assertThat(
            call(session, "start_task", Map.of("identifier", id, "version", task.version()))
                .path("isError")
                .asBoolean())
        .isFalse();
    assertThat(
            call(session, "add_comment", Map.of("identifier", id, "body", "Implemented the change"))
                .path("isError")
                .asBoolean())
        .isFalse();
    task = tasks.get(owner, id);
    assertThat(
            call(session, "complete_task", Map.of("identifier", id, "version", task.version()))
                .path("isError")
                .asBoolean())
        .isFalse();
    task = tasks.get(owner, id);
    assertThat(task.status()).isEqualTo("IN_REVIEW");
    assertThat(
            call(
                    session,
                    "update_task_status",
                    Map.of("identifier", id, "version", task.version(), "status", "DONE"))
                .path("isError")
                .asBoolean())
        .isTrue();
    assertThat(
            call(session, "get_task_comments", Map.of("identifier", id))
                .path("structuredContent")
                .path("data")
                .path("total")
                .asLong())
        .isEqualTo(1);
    assertThat(
            db.one(
                Long.class,
                "SELECT count(*) FROM audit WHERE actor_id=? AND action='MCP_INVOCATION'",
                agent.id()))
        .isGreaterThan(5);
    tokens.revoke(owner, agent.tokenId());
    assertThat(rpc(token, session, "tools/list", Map.of(), 9).status()).isEqualTo(401);
  }

  @Test
  void browserPageUrlsServeTheAppBeforeSignIn() throws Exception {
    var client = HttpClient.newHttpClient();
    String base = "http://localhost:" + port;
    for (String path : List.of(
        "/", "/inbox", "/my-issues", "/approvals", "/issues?project=123",
        "/board", "/projects", "/settings", "/settings/account", "/settings/people",
        "/settings/mcp-connections", "/settings/audit-log", "/projects/123/settings",
        "/inbox/", "/future-page")) {
      var response = client.send(
          HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).as(path).isEqualTo(200);
      assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("text/html");
      assertThat(response.body()).as(path).contains("<div id=\"app\">");
    }
    for (String path : List.of("/api/tasks", "/api/projects", "/api/not-a-page", "/actuator/env", "/mcp")) {
      var response = client.send(
          HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).as(path).isEqualTo(401);
      assertThat(response.body()).doesNotContain("<div id=\"app\">");
    }
  }

  @Test
  void sveltekitBootstrapAndAssetsAreAllowedBeforeSignIn() throws Exception {
    var client = HttpClient.newHttpClient();
    String base = "http://localhost:" + port;
    var response = client.send(
        HttpRequest.newBuilder(URI.create(base + "/inbox")).GET().build(),
        HttpResponse.BodyHandlers.ofString());
    String policy = response.headers().firstValue("Content-Security-Policy").orElseThrow();
    assertThat(policy).contains("script-src 'self' 'sha256-");
    assertThat(policy.substring(policy.indexOf("script-src"), policy.indexOf(";", policy.indexOf("script-src"))))
        .doesNotContain("unsafe-inline");
    var scripts = java.util.regex.Pattern.compile("<script\\b[^>]*>(.*?)</script>", java.util.regex.Pattern.DOTALL)
        .matcher(response.body());
    int count = 0;
    while (scripts.find()) {
      if (scripts.group(1).isBlank()) continue;
      byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
          .digest(scripts.group(1).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      assertThat(policy).contains("'sha256-" + Base64.getEncoder().encodeToString(digest) + "'");
      count++;
    }
    assertThat(count).isPositive();
    var assets = java.util.regex.Pattern.compile("[\"'](/_app/[^\"']+)[\"']").matcher(response.body());
    int assetCount = 0;
    while (assets.find()) {
      String path = assets.group(1);
      var asset = client.send(
          HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      assertThat(asset.statusCode()).as(path).isEqualTo(200);
      assertThat(asset.headers().firstValue("Content-Type").orElse("")).doesNotContain("text/html");
      assertThat(asset.body()).isNotBlank();
      assetCount++;
    }
    assertThat(assetCount).isPositive();
  }

  @Test
  void browserSessionRequiresCsrfAndServesRealApi() throws Exception {
    var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    var client = HttpClient.newBuilder().cookieHandler(cookies).build();
    String base = "http://localhost:" + port;
    var csrf =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/api/csrf")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    JsonNode c = json.readTree(csrf.body());
    var noCsrf =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/api/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "username=owner&password=test-password-12345"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(noCsrf.statusCode()).isEqualTo(403);
    var login =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/api/login"))
                .header(c.path("header").asText(), c.path("token").asText())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "username=owner&password=test-password-12345"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(login.statusCode()).isEqualTo(200);
    var response =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/api/projects")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(json.readTree(response.body()).isArray()).isTrue();
    assertThat(
            client
                .send(
                    HttpRequest.newBuilder(URI.create(base + "/api/projects"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode())
        .isEqualTo(403);
    c =
        json.readTree(
            client
                .send(
                    HttpRequest.newBuilder(URI.create(base + "/api/csrf")).GET().build(),
                    HttpResponse.BodyHandlers.ofString())
                .body());
    var creation =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/api/tasks"))
                .header(c.path("header").asText(), c.path("token").asText())
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        json.writeValueAsString(
                            Map.of("project", project, "task", input("REST proposal")))))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(creation.statusCode()).isEqualTo(200);
    JsonNode created = json.readTree(creation.body());
    String id = created.path("identifier").asText();
    assertThat(created.path("approval").asText()).isEqualTo("PENDING");
    var transition =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/api/tasks/" + id + "/status"))
                .header(c.path("header").asText(), c.path("token").asText())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"version\":1,\"status\":\"READY\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(transition.statusCode()).isEqualTo(409);
    assertThat(json.readTree(transition.body()).path("code").asText()).isEqualTo("CONFLICT");
    String session = mcpSession(token);
    assertThat(
            call(session, "get_task", Map.of("identifier", id))
                .path("structuredContent")
                .path("data")
                .path("task")
                .path("title")
                .asText())
        .isEqualTo("REST proposal");
  }
}
