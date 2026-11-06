import { expect, test } from '@playwright/test';

const project = { id: 42, key: 'TEST', name: 'Sample project', description: '', repository: '' };

test.beforeEach(async ({ context }) => {
  await context.route('**/api/**', async (route) => {
    const url = new URL(route.request().url());
    let body: unknown;
    switch (url.pathname) {
      case '/api/me': body = { id: 7, username: 'owner', kind: 'OWNER' }; break;
      case '/api/projects': body = [project]; break;
      case '/api/projects/42': body = { project, participants: [] }; break;
      case '/api/notifications': body = { items: [], total: 0, unread: 0 }; break;
      case '/api/tasks/TEST-1': body = {
        task: {
          id: 1, projectId: 42, identifier: 'TEST-1', title: 'Deep-linked issue',
          description: '', type: 'FEATURE', priority: 'MEDIUM', status: 'BACKLOG',
          approval: 'APPROVED', creatorId: 7, assigneeId: null, category: '', labels: '[]',
          subscribed: false, archived: false, version: 1, revision: 1,
          createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
        },
        subscribed: false, attachments: [], links: [], approvals: [],
      }; break;
      case '/api/tasks/TEST-1/comments':
      case '/api/tasks/TEST-1/activity': body = { items: [], total: 0, page: 0, size: 50 }; break;
      case '/api/tasks': body = { items: [], total: 0, page: 0, size: 50 }; break;
      case '/api/csrf': body = { token: 'test-csrf', header: 'X-CSRF-TOKEN' }; break;
      case '/api/invitations/accept': body = { project_id: 42 }; break;
      case '/api/users':
      case '/api/projects/42/roles':
      case '/api/projects/42/invitations':
      case '/api/tokens':
      case '/api/audit': body = []; break;
      default: throw new Error(`Unexpected API request: ${url.pathname}`);
    }
    await route.fulfill({ json: body });
  });
});

for (const status of [403, 404]) {
  test(`unavailable project (${status}) keeps the signed-in workspace usable`, async ({ page }) => {
    await page.route('**/api/projects/999999', (route) => route.fulfill({
      status, json: { message: 'Project unavailable.' },
    }));
    await page.goto('/issues?project=999999');
    await expect(page.getByRole('alert')).toContainText('Project unavailable.');
    await expect(page.getByRole('heading', { name: 'Welcome to Forge' })).toHaveCount(0);
    await page.locator('.sidebar').getByRole('link', { name: 'All projects', exact: false }).click();
    await expect(page).toHaveURL('/issues');
    await expect(page.getByRole('alert')).toHaveCount(0);
    await expect(page.getByRole('heading', { name: 'All Issues', exact: false })).toBeVisible();
  });
}

test('an unauthenticated visitor still sees sign in', async ({ page }) => {
  await page.route('**/api/me', (route) => route.fulfill({
    status: 401, json: { message: 'Authentication required.' },
  }));
  await page.goto('/issues');
  await expect(page.getByRole('heading', { name: 'Welcome to Forge' })).toBeVisible();
  await expect(page.locator('.app-shell')).toHaveCount(0);
});

test('navigation shows loading until issues arrive, then shows the empty state', async ({ page }) => {
  await page.goto('/projects');
  await expect(page.getByRole('heading', { name: 'Projects', exact: false })).toBeVisible();
  let release!: () => void;
  const responseReady = new Promise<void>((resolve) => { release = resolve; });
  await page.route('**/api/tasks?**', async (route) => {
    await responseReady;
    await route.fulfill({ json: { items: [], total: 0, page: 0, size: 50 } });
  });
  try {
    await page.locator('.sidebar').getByRole('link', { name: 'All Issues' }).click();
    await expect(page.getByRole('status')).toHaveText('Loading content…');
    await expect(page.getByText('No issues here yet', { exact: true })).toHaveCount(0);
    await expect(page.getByText('0 issues', { exact: true })).toHaveCount(0);
  } finally {
    release();
  }
  await expect(page.getByRole('status')).toHaveCount(0);
  await expect(page.getByText('No issues here yet', { exact: true })).toBeVisible();
});

test('a failed filter request clears the loading indicator and shows the error', async ({ page }) => {
  await page.goto('/issues');
  await expect(page.getByText('No issues here yet', { exact: true })).toBeVisible();
  let release!: () => void;
  const responseReady = new Promise<void>((resolve) => { release = resolve; });
  await page.route('**/api/tasks?**', async (route) => {
    await responseReady;
    await route.fulfill({ status: 500, json: { message: 'Unable to load issues.' } });
  });
  try {
    await page.getByRole('combobox', { name: 'Status filter', exact: true }).selectOption('READY');
    await expect(page.getByRole('status')).toHaveText('Loading issues…');
    await expect(page.getByText('No issues here yet', { exact: true })).toHaveCount(0);
  } finally {
    release();
  }
  await expect(page.getByRole('status')).toHaveCount(0);
  await expect(page.getByRole('alert')).toContainText('Unable to load issues.');
});

test('issue columns fit their contents and priority indicators match their values', async ({ page }) => {
  const priorities = ['NONE', 'LOW', 'MEDIUM', 'HIGH', 'URGENT'];
  const approvals = ['PENDING', 'APPROVED', 'CHANGES_REQUESTED', 'REJECTED', 'PENDING'];
  await page.route('**/api/tasks?**', async (route) => {
    const items = priorities.map((priority, index) => ({
      id: index + 1, projectId: 42, identifier: `TEST-${index + 1}`,
      title: index === 1 ? 'A longer issue title to size the column' : 'Issue',
      description: '', type: 'FEATURE', priority, status: 'BACKLOG', approval: approvals[index],
      creatorId: 7, assigneeId: null, category: '', labels: '[]', subscribed: false,
      archived: false, version: 1, revision: 1,
      createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
    }));
    await route.fulfill({ json: { items, total: items.length, page: 0, size: 50 } });
  });
  await page.goto('/issues');
  const rows = page.locator('.compact-issues .issue-row');
  await expect(rows).toHaveCount(5);
  for (const [index, priority] of priorities.entries()) {
    const marker = rows.nth(index).locator('.priority');
    await expect(marker).toHaveAttribute('title', `Priority: ${priority[0] + priority.slice(1).toLowerCase()}`);
    const style = await marker.evaluate((element) => {
      const pseudo = getComputedStyle(element, '::before');
      return { content: pseudo.content, background: pseudo.backgroundColor, opacity: pseudo.opacity, color: pseudo.color };
    });
    expect(style.content).toBe(priority === 'HIGH' || priority === 'URGENT' ? '"!"' : '""');
    if (priority === 'LOW') expect(style.opacity).toBe('0.5');
    if (priority === 'MEDIUM') expect(style.background).toBe('rgb(88, 155, 255)');
    if (priority === 'HIGH') expect(style.color).toBe('rgb(242, 201, 76)');
    if (priority === 'URGENT') expect(style.color).toBe('rgb(240, 91, 100)');
  }
  const authors = rows.locator('.creator-cell > span:last-child');
  for (const author of await authors.all()) {
    expect(await author.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true);
  }
  const titleLefts = await rows.locator('.issue-title').evaluateAll((elements) =>
    elements.map((element) => element.getBoundingClientRect().left),
  );
  expect(new Set(titleLefts).size).toBe(1);
  await expect(page.locator('.compact-issues .table-head')).toContainText('Approval');
  await expect(rows.locator('.approval-cell')).toHaveText([
    '◇ Pending', 'Approved', 'Changes requested', '● Rejected', '◇ Pending',
  ]);
  await expect(rows.first().locator('.approval-tag')).toHaveCSS('background-color', 'rgba(0, 0, 0, 0)');
  await expect(rows.first().locator('.approval-tag')).toHaveCSS('border-top-width', '0px');
  const approvalLefts = await rows.locator('.approval-cell').evaluateAll((elements) =>
    elements.map((element) => element.getBoundingClientRect().left),
  );
  expect(new Set(approvalLefts).size).toBe(1);
  const rowHeight = await rows.first().evaluate((element) => element.getBoundingClientRect().height);
  expect(rowHeight).toBeLessThan(42);
});

test('page shells contain rendered content before JavaScript starts', async ({ request }) => {
  for (const [path, title] of [['/inbox', 'Inbox'], ['/settings/people/', 'People · Settings']]) {
    const response = await request.get(path);
    expect(response.ok()).toBeTruthy();
    const html = await response.text();
    expect(html).toContain(`<title>${title} · Forge</title>`);
    expect(html).toContain('Opening your workspace…');
    expect(html).toContain('href="/_app/');
  }
});

test('native links navigate with history and keep the workspace mounted', async ({ page }) => {
  let meRequests = 0;
  page.on('request', (request) => {
    if (new URL(request.url()).pathname === '/api/me') meRequests++;
  });
  await page.goto('/inbox');
  await expect(page.getByRole('heading', { name: 'Inbox', exact: true })).toBeVisible();
  const link = page.locator('.sidebar').getByRole('link', { name: 'My Issues', exact: false });
  await expect(link).toHaveAttribute('href', '/my-issues');
  const tasks = page.waitForRequest((request) => {
    const url = new URL(request.url());
    return url.pathname === '/api/tasks' && url.searchParams.get('assignee') === '7';
  });
  await link.click();
  await tasks;
  await expect(page).toHaveURL('/my-issues');
  await expect(page.getByRole('heading', { name: 'My Issues', exact: false })).toBeVisible();
  expect(meRequests).toBe(1);
  await page.goBack();
  await expect(page.getByRole('heading', { name: 'Inbox', exact: true })).toBeVisible();
  await page.goForward();
  await expect(page).toHaveURL('/my-issues');
  await page.reload();
  await expect(page.getByRole('heading', { name: 'My Issues', exact: false })).toBeVisible();
});

test('project links and the project selector update the URL and issue query', async ({ page }) => {
  await page.goto('/board');
  await page.locator('.sidebar .project-nav').filter({ hasText: 'Sample project' }).click();
  await expect(page).toHaveURL('/board?project=42');
  await expect(page.getByRole('combobox', { name: 'Project', exact: true })).toHaveValue('42');
  await page.getByRole('link', { name: 'List', exact: false }).click();
  await expect(page).toHaveURL('/issues?project=42');
  const tasks = page.waitForRequest((request) => {
    const url = new URL(request.url());
    return url.pathname === '/api/tasks' && !url.searchParams.has('project');
  });
  await page.getByRole('combobox', { name: 'Project', exact: true }).selectOption('');
  await tasks;
  await expect(page).toHaveURL('/issues');
  await page.goBack();
  await expect(page).toHaveURL('/issues?project=42');
  await expect(page.getByRole('combobox', { name: 'Project', exact: true })).toHaveValue('42');
});

test('settings use distinct file routes and survive refresh', async ({ page }) => {
  await page.goto('/settings/account?project=42');
  await page.getByRole('link', { name: 'People', exact: true }).click();
  await expect(page).toHaveURL('/settings/people?project=42');
  await expect(page.getByRole('heading', { name: 'People', exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('heading', { name: 'People', exact: true })).toBeVisible();
  await page.getByRole('link', { name: 'MCP connections', exact: true }).click();
  await expect(page).toHaveURL('/settings/mcp-connections?project=42');
  await expect(page.getByRole('heading', { name: 'MCP connections', exact: true })).toBeVisible();
});

test('project settings with runtime IDs load and survive refresh', async ({ page }) => {
  await page.goto('/projects/42/settings');
  await expect(page.getByRole('heading', { name: 'Project details', exact: true })).toBeVisible();
  await expect(page.getByRole('textbox', { name: 'Name', exact: true })).toHaveValue('Sample project');
  await page.reload();
  await expect(page.getByRole('textbox', { name: 'Name', exact: true })).toHaveValue('Sample project');
});

test('invitation fragments lead to the accepted project through native navigation', async ({ page }) => {
  await page.goto('/#invite=test-invitation');
  await expect(page.getByRole('heading', { name: 'Join a project' })).toBeVisible();
  await page.getByRole('button', { name: 'Accept invitation' }).click();
  await expect(page).toHaveURL('/issues?project=42');
  await expect(page.getByRole('combobox', { name: 'Project', exact: true })).toHaveValue('42');
  await page.reload();
  await expect(page.getByRole('heading', { name: 'All Issues', exact: false })).toBeVisible();
});

test('task fragments open and close without changing the page or project', async ({ page }) => {
  await page.goto('/my-issues?project=42#task=TEST-1');
  await expect(page.getByRole('heading', { name: 'Deep-linked issue', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Close issue', exact: true }).click();
  await expect(page).toHaveURL('/my-issues?project=42');
  await expect(page.getByRole('heading', { name: 'Deep-linked issue', exact: true })).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'My Issues', exact: false })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('combobox', { name: 'Project', exact: true })).toHaveValue('42');
});

test('sidebar links support opening a page in a new tab', async ({ page, context }) => {
  await page.goto('/inbox');
  const opened = context.waitForEvent('page');
  await page.locator('.sidebar').getByRole('link', { name: 'My Issues', exact: false })
    .click({ modifiers: ['ControlOrMeta'] });
  const tab = await opened;
  await expect(tab).toHaveURL('/my-issues');
  await expect(tab.getByRole('heading', { name: 'My Issues', exact: false })).toBeVisible();
  await expect(page).toHaveURL('/inbox');
});
