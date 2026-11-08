import { createContext, type Snippet } from 'svelte';

// Page snippets share the workspace's authenticated state and action handlers.
export interface WorkspaceViews {
  issues: Snippet;
  inbox: Snippet;
  projectsPage: Snippet;
  projectSettings: Snippet;
  settings: Snippet;
}

export const [getWorkspaceViews, setWorkspaceViews] = createContext<WorkspaceViews>();
