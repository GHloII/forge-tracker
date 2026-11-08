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
  if (project) {
    const params = { projectId: project };
    switch (view) {
      case 'All Issues': return resolve('/projects/[projectId=integer]/issues', params);
      case 'My Issues': return resolve('/projects/[projectId=integer]/my-issues', params);
      case 'Approvals': return resolve('/projects/[projectId=integer]/approvals', params);
      case 'Board': return resolve('/projects/[projectId=integer]/board', params);
    }
  }
  return resolve(path);
}
