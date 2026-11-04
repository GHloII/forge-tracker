import { resolve } from '$app/paths';

// Typed link destinations; SvelteKit owns route matching and navigation.
const paths = {
  Inbox: '/inbox',
  'My Issues': '/my-issues',
  Approvals: '/approvals',
  'All Issues': '/issues',
  Board: '/board',
  Projects: '/projects',
} as const;
const sections = {
  Account: '/settings/account',
  People: '/settings/people',
  'MCP connections': '/settings/mcp-connections',
  'Audit log': '/settings/audit-log',
} as const;

export function pageUrl(view: string, project = '', section = 'Account'): string {
  if (view === 'Project settings')
    return resolve('/projects/[projectId=integer]/settings', { projectId: project });
  const path = view === 'Settings'
    ? sections[section as keyof typeof sections] ?? '/settings/account'
    : paths[view as keyof typeof paths] ?? '/issues';
  return resolve(path) + (project ? `?project=${encodeURIComponent(project)}` : '');
}
