import assert from "node:assert/strict";
import { mkdir, readFile, writeFile } from "node:fs/promises";

// Run only against a disposable verification deployment: this creates real records.
const base = process.env.SITE_URL ?? "http://127.0.0.1:18080";
const username = process.env.TEST_USERNAME ?? "owner";
const password = process.env.TEST_PASSWORD;
assert(password, "Set TEST_PASSWORD for the isolated verification instance.");
await mkdir(".tools", { recursive: true });
const cookies = new Map();
let csrf;
async function request(path, method = "GET", body) {
  const headers = {
    Cookie: [...cookies].map(([k, v]) => `${k}=${v}`).join("; "),
  };
  if (csrf && method !== "GET") headers[csrf.header] = csrf.token;
  if (
    body !== undefined &&
    !(body instanceof FormData) &&
    !(body instanceof URLSearchParams)
  )
    headers["Content-Type"] = "application/json";
  const response = await fetch(base + "/api" + path, {
    method,
    headers,
    body:
      body === undefined
        ? undefined
        : body instanceof FormData || body instanceof URLSearchParams
          ? body
          : JSON.stringify(body),
  });
  for (const cookie of response.headers.getSetCookie()) {
    const [name, ...value] = cookie.split(";")[0].split("=");
    cookies.set(name, value.join("="));
  }
  assert(
    response.ok,
    `${method} ${path}: ${response.status} ${await (response.ok ? Promise.resolve("") : response.text())}`,
  );
  return response;
}
async function api(path, method, body) {
  return (await request(path, method, body)).json();
}
csrf = await api("/csrf");
await api("/login", "POST", new URLSearchParams({ username, password }));
csrf = await api("/csrf");

if (process.argv.includes("--verify")) {
  const state = JSON.parse(await readFile(".tools/smoke-state.json", "utf8"));
  const details = await api("/tasks/" + state.identifier);
  assert.equal(details.task.title, "Revised implementation scope");
  assert.equal(details.task.approval, "PENDING");
  assert.equal(details.task.revision, 2);
  assert.equal(
    (await api("/tasks/" + state.identifier + "/comments")).total,
    1,
  );
  assert.equal(
    (await request("/attachments/" + state.attachment)).headers.get(
      "content-type",
    ),
    "image/png",
  );
  console.log(
    "PASS: requirements, approvals, comments and image survive application restart.",
  );
  process.exit(0);
}

const key = "S" + Date.now().toString(36).slice(-7).toUpperCase();
const project = await api("/projects", "POST", {
  key,
  name: "Deployment verification",
  description: "Isolated smoke-test workspace",
  repository: "https://github.com/team/game",
});
const agent = await api("/users", "POST", {
  username: key.toLowerCase(),
  displayName: "Verification agent",
  kind: "AGENT",
});
await api(`/projects/${project.id}/members`, "POST", {
  user: agent.id,
  permission: "WRITE",
});
const credentials = await api("/tokens", "POST", {
  user: agent.id,
  project: project.id,
  name: "Deployment verification",
  permission: "WRITE",
});
let session;
let sequence = 0;
async function rpc(method, params) {
  const headers = {
    Authorization: "Bearer " + credentials.token,
    Accept: "application/json, text/event-stream",
    "Content-Type": "application/json",
  };
  if (session) {
    headers["Mcp-Session-Id"] = session;
    headers["MCP-Protocol-Version"] = "2025-06-18";
  }
  const response = await fetch(base + "/mcp", {
    method: "POST",
    headers,
    body: JSON.stringify({ jsonrpc: "2.0", id: ++sequence, method, params }),
  });
  assert(response.ok, `MCP ${method}: ${response.status}`);
  session = response.headers.get("Mcp-Session-Id") ?? session;
  const raw = await response.text();
  const data = raw.startsWith("{")
    ? raw
    : raw
        .split("\n")
        .filter((l) => l.startsWith("data:"))
        .map((l) => l.slice(5).trim())
        .join("\n");
  return JSON.parse(data);
}
await rpc("initialize", {
  protocolVersion: "2025-06-18",
  capabilities: {},
  clientInfo: { name: "deployment-verification", version: "1" },
});
const listed = await rpc("tools/list", {});
assert.equal(listed.result.tools.length, 15);
async function tool(name, args, fail = false) {
  const response = await rpc("tools/call", { name, arguments: args });
  assert.equal(
    Boolean(response.result.isError),
    fail,
    name + ": unexpected result",
  );
  return response.result.structuredContent;
}
let task = (
  await tool("create_task", {
    project: project.id,
    title: "Container task",
    description: "Requirements for deployment verification",
  })
).data;
assert.equal(task.approval, "PENDING");
await tool(
  "update_task_status",
  { identifier: task.identifier, version: task.version, status: "READY" },
  true,
);
task = await api(`/tasks/${task.identifier}/approval`, "POST", {
  version: task.version,
  decision: "APPROVED",
  comment: "Verified scope",
});
task = (
  await tool("assign_task", {
    identifier: task.identifier,
    version: task.version,
  })
).data;
task = (
  await tool("update_task_status", {
    identifier: task.identifier,
    version: task.version,
    status: "READY",
  })
).data;
task = (
  await tool("start_task", {
    identifier: task.identifier,
    version: task.version,
  })
).data;
await tool("add_comment", {
  identifier: task.identifier,
  body: "Implementation and checks complete.",
});
await tool("link_pull_request", {
  identifier: task.identifier,
  url: "https://github.com/team/game/pull/42",
});
task = (
  await tool("complete_task", {
    identifier: task.identifier,
    version: task.version,
  })
).data;
assert.equal(task.status, "IN_REVIEW");
await tool(
  "update_task_status",
  { identifier: task.identifier, version: task.version, status: "DONE" },
  true,
);
task = await api(`/tasks/${task.identifier}/status`, "POST", {
  version: task.version,
  status: "DONE",
});
task = (
  await tool("update_task", {
    identifier: task.identifier,
    version: task.version,
    title: "Revised implementation scope",
    description: "Additional acceptance requirement",
  })
).data;
assert.equal(task.approval, "PENDING");
assert.equal(task.status, "BACKLOG");
assert.equal(task.revision, 2);
const image = new FormData();
image.append(
  "file",
  new Blob(
    [
      Buffer.from(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aX1sAAAAASUVORK5CYII=",
        "base64",
      ),
    ],
    { type: "image/png" },
  ),
  "verification.png",
);
const attachment = await api(
  `/tasks/${task.identifier}/attachments`,
  "POST",
  image,
);
assert.equal((await api("/notifications")).unread > 0, true);
await api(`/tokens/${credentials.id}`, "DELETE");
const revoked = await fetch(base + "/mcp", {
  method: "POST",
  headers: {
    Authorization: "Bearer " + credentials.token,
    "Content-Type": "application/json",
  },
  body: "{}",
});
assert.equal(revoked.status, 401);
await writeFile(
  ".tools/smoke-state.json",
  JSON.stringify({ identifier: task.identifier, attachment: attachment.id }),
);
console.log(
  "PASS: REST login/CSRF, MCP lifecycle, approval gates, human acceptance, revision invalidation, notifications, image upload and token revocation.",
);
