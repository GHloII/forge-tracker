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
      case '/api/tokens':
      case '/api/audit': body = []; break;
      default: throw new Error(`Unexpected API request: ${url.pathname}`);
    }
    await route.fulfill({ json: body });
  });
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
