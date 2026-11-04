export interface Actor {
  id: number;
  username: string;
  kind: 'OWNER' | 'DEVELOPER' | 'AGENT';
}
export interface Project {
  id: number;
  key: string;
  name: string;
  description: string;
  repository: string;
}
export interface Person {
  id: number;
  username: string;
  display_name: string;
  kind: string;
  permission?: string;
  role_id?: number;
  role_name?: string;
  enabled?: number;
}
export interface Task {
  id: number;
  projectId: number;
  identifier: string;
  title: string;
  description: string;
  type: string;
  priority: string;
  status: string;
  approval: string;
  creatorId: number;
  assigneeId: number | null;
  category: string;
  labels: string;
  archived: boolean;
  version: number;
  revision: number;
  createdAt: string;
  updatedAt: string;
}
export interface Page<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}
export interface Entry {
  [key: string]: any;
}
export const statuses = ['BACKLOG', 'READY', 'IN_PROGRESS', 'IN_REVIEW', 'DONE', 'CANCELLED'];
export const priorities = ['URGENT', 'HIGH', 'MEDIUM', 'LOW', 'NONE'];
export const types = ['FEATURE', 'BUG', 'IMPROVEMENT', 'RESEARCH'];
export const label = (s: string) =>
  s
    .toLowerCase()
    .replaceAll('_', ' ')
    .replace(/^./, (c) => c.toUpperCase());
let csrf = { token: '', header: 'X-CSRF-TOKEN' };
export async function refreshCsrf() {
  csrf = await (await fetch('/api/csrf')).json();
}
export async function api<T = any>(path: string, method = 'GET', body?: unknown): Promise<T> {
  const headers: Record<string, string> = {};
  if (method !== 'GET') {
    if (!csrf.token) await refreshCsrf();
    headers[csrf.header] = csrf.token;
  }
  if (body !== undefined && !(body instanceof FormData) && !(body instanceof URLSearchParams))
    headers['Content-Type'] = 'application/json';
  const res = await fetch('/api' + path, {
    method,
    headers,
    body:
      body === undefined
        ? undefined
        : body instanceof FormData || body instanceof URLSearchParams
          ? body
          : JSON.stringify(body),
  });
  if (!res.ok) {
    const error = await res.json().catch(() => ({ message: `Request failed (${res.status})` }));
    throw new Error(error.message ?? 'Request failed');
  }
  return res.json();
}
