<script lang="ts">
  import { onMount, tick, untrack, type Snippet } from 'svelte';
  import { setWorkspaceViews } from './workspace-views';
  import { afterNavigate, goto, replaceState } from '$app/navigation';
  import { page as currentPage } from '$app/state';
  import { pageUrl } from './navigation';

  let { view, section = 'Account', children }: { view: string; section?: string; children: Snippet } = $props();
  setWorkspaceViews({
    get issues() { return issues; },
    get inbox() { return inbox; },
    get projectsPage() { return projectsPage; },
    get projectSettings() { return projectSettings; },
    get settings() { return settings; },
  });
  import Markdown from './Markdown.svelte';
  import ProjectSettings from './ProjectSettings.svelte';
  import {
    api,
    refreshCsrf,
    statuses,
    priorities,
    types,
    label,
    type Actor,
    type Project,
    type Person,
    type Task,
    type Page,
    type Entry,
  } from './api';

  let me = $state<Actor | null>(null),
    loading = $state(true),
    error = $state(''),
    busy = $state(false);
  let invitationToken = $state('');
  let inviteSignIn = $state(false);
  let registrationName = $state(''),
    confirmation = $state('');
  let username = $state(''),
    password = $state('');
  let project = $state(''),
    projects = $state<Project[]>([]),
    people = $state<Person[]>([]);
  let projectPeople = $state<Record<number, Person[]>>({});
  let tasks = $state<Task[]>([]),
    total = $state(0),
    page = $state(0),
    query = $state(''),
    statusFilter = $state(''),
    priorityFilter = $state(''),
    sort = $state('updated'),
    showArchived = $state(false),
    subscribed = $state(false);
  let selected = $state<Set<string>>(new Set()),
    focusIndex = $state(0),
    searchInput = $state<HTMLInputElement>();
  let rowReview = $state<{ identifier: string; decision: string } | null>(null);
  let rowFeedback = $state('');
  let revisionPreview = $state<Entry | null>(null);
  let detail = $state<Entry | null>(null),
    task = $derived<Task | null>(detail?.task ?? null),
    editing = $state(false),
    draft = $state({
      title: '',
      description: '',
      type: 'FEATURE',
      priority: 'MEDIUM',
      category: '',
      labels: '',
    }),
    preview = $state(false);
  let comments = $state<Entry[]>([]),
    activity = $state<Entry[]>([]),
    commentPage = $state(0),
    commentTotal = $state(0),
    activityPage = $state(0),
    activityTotal = $state(0),
    tab = $state('Comments'),
    comment = $state(''),
    feedback = $state('');
  let linkKind = $state('PR'),
    linkUrl = $state('');
  let createOpen = $state(false),
    createProject = $state(''),
    newTask = $state({
      title: '',
      description: '',
      type: 'FEATURE',
      priority: 'MEDIUM',
      category: '',
      labels: '',
    });
  let notifications = $state<Entry[]>([]),
    notificationPage = $state(0),
    notificationTotal = $state(0),
    unread = $state(0);
  let settingsTab = $state(untrack(() => section)),
    allUsers = $state<Person[]>([]),
    tokens = $state<Entry[]>([]),
    audit = $state<Entry[]>([]),
    auditPage = $state(0);
  let userForm = $state({ username: '', displayName: '', kind: 'DEVELOPER', password: '', account: '' });
  let freshToken = $state(''), mcpAccountName = $state(''), mcpProjects = $state<number[] | null>(null);
  let projectForm = $state({ key: '', name: '', description: '', repository: '' }),
    projectEditId = $state<number | null>(null),
    projectFormOpen = $state(false);
  let importOpen = $state(false),
    importProject = $state(''),
    importSource = $state('TODO.md'),
    importMarkdown = $state(''),
    importPreview = $state<Entry[] | null>(null);
  let passwordForm = $state({ current: '', replacement: '' }),
    shortcuts = $state(false),
    theme = $state('dark'),
    toast = $state('');
  const navigation = [
    { name: 'Inbox', icon: '◫' },
    { name: 'My Issues', icon: '◎' },
    { name: 'Approvals', icon: '◇' },
    { name: 'All Issues', icon: '☷' },
    { name: 'Board', icon: '▥' },
    { name: 'Projects', icon: '▣' },
  ];
  const colors: Record<string, string> = {
    BACKLOG: '#85868f',
    READY: '#bbc0cc',
    IN_PROGRESS: '#eab957',
    IN_REVIEW: '#b49af3',
    DONE: '#80bca5',
    CANCELLED: '#777780',
  };
  let currentProject = $derived(projects.find((p) => String(p.id) === project));
  let owner = $derived(me?.kind === 'OWNER');
  let canManage = $derived(managesProject(task?.projectId ?? Number(project)));
  let canWrite = $derived(
    writesProject(task?.projectId ?? Number(project)),
  );
  let canEdit = $derived(
    canWrite && (canManage || task?.creatorId === me?.id || task?.assigneeId === me?.id),
  );

  function managesProject(id: number) {
    return owner || projectPeople[id]?.find((p) => p.id === me?.id)?.permission === 'MANAGE';
  }
  function writesProject(id: number) {
    return owner || ['WRITE', 'MANAGE'].includes(projectPeople[id]?.find((p) => p.id === me?.id)?.permission ?? '');
  }
  function assignablePeople(id: number) {
    return (projectPeople[id] ?? []).filter((p) => p.kind === 'OWNER' || ['WRITE', 'MANAGE'].includes(p.permission ?? ''));
  }
  function canAssignRow(item: Task) {
    return !item.archived && (managesProject(item.projectId) ||
      (writesProject(item.projectId) && !item.assigneeId && item.approval === 'APPROVED'));
  }
  async function assignRow(item: Task, assignee: string) {
    await run(async () => {
      try {
        await api(`/tasks/${item.identifier}/assign`, 'POST', { version: item.version, assignee: assignee ? Number(assignee) : null });
      } finally {
        await loadTasks();
        if (task?.identifier === item.identifier) detail = await api(`/tasks/${item.identifier}`);
      }
      notify(assignee ? 'Assignee updated' : 'Issue unassigned');
    });
  }
  async function run(action: () => Promise<void>) {
    if (busy) return;
    busy = true;
    error = '';
    try {
      await action();
    } catch (e) {
      error = (e as Error).message;
    } finally {
      busy = false;
    }
  }
  function notify(text: string) {
    toast = text;
    setTimeout(() => (toast = ''), 3500);
  }
  function name(id: number | null) {
    return (people.find((p) => p.id === id) ?? Object.values(projectPeople).flat().find((p) => p.id === id))?.display_name ?? (id ? `User ${id}` : 'Unassigned');
  }
  function date(value: string) {
    return new Date(value).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
  }
  function labels(value: string): string[] {
    try {
      return JSON.parse(value);
    } catch {
      return [];
    }
  }
  function input(value: typeof draft) {
    return {
      ...value,
      labels: value.labels
        .split(',')
        .map((l) => l.trim())
        .filter(Boolean),
    };
  }
  function setTheme(value: string) {
    theme = value;
    localStorage.setItem('forge-theme', value);
    document.documentElement.dataset.theme = value;
  }
  async function initialize() {
    me = await api<Actor>('/me');
    projects = await api<Project[]>('/projects');
    await Promise.all([
      loadPageData(),
      ...(view !== 'Inbox' ? [loadNotifications()] : []),
    ]);
  }
  async function login(event: SubmitEvent) {
    event.preventDefault();
    await run(async () => {
      let invitedProject: number | undefined;
      if (invitationToken && !inviteSignIn) {
        if (password !== confirmation) throw new Error('Passwords do not match.');
        const result = await api<Entry>('/register', 'POST', {
          token: invitationToken,
          username,
          displayName: registrationName,
          password,
        });
        invitedProject = result.project_id;
        invitationToken = '';
        confirmation = '';
        replaceState(currentPage.url.pathname + currentPage.url.search, currentPage.state);
        notify('Account created');
      }
      await api('/login', 'POST', new URLSearchParams({ username, password }));
      password = '';
      await refreshCsrf();
      if (invitationToken && inviteSignIn) {
        const result = await api<Entry>('/invitations/accept', 'POST', { token: invitationToken });
        invitedProject = result.project_id;
        invitationToken = '';
        replaceState(currentPage.url.pathname + currentPage.url.search, currentPage.state);
      }
      if (invitedProject) {
        await goto(href('All Issues', String(invitedProject)), { replaceState: true });
      }
      await initialize();
    });
  }
  async function logout() {
    await run(async () => {
      await api('/logout', 'POST');
      me = null;
      detail = null;
      password = '';
      freshToken = '';
      tokens = [];
      allUsers = [];
      audit = [];
      people = [];
      projectPeople = {};
      projects = [];
      tasks = [];
      notifications = [];
      unread = 0;
      project = '';
      settingsTab = 'Account';
      passwordForm = { current: '', replacement: '' };
      userForm = { username: '', displayName: '', kind: 'DEVELOPER', password: '', account: '' };
      selected = new Set();
      comments = [];
      activity = [];
      createOpen = false;
      projectFormOpen = false;
      importOpen = false;
      importMarkdown = '';
      importPreview = null;
      routeSequence++;
      detailSequence++;
      loadSequence++;
      if (!invitationToken) replaceState(currentPage.url.pathname + currentPage.url.search, currentPage.state);
      await refreshCsrf();
    });
  }
  async function loadPeople() {
    const actorId = me?.id;
    const selectedProject = project;
    if (selectedProject) {
      const data = await api<Entry>(`/projects/${selectedProject}`);
      if (me?.id !== actorId || project !== selectedProject) return;
      projectPeople[Number(selectedProject)] = data.participants;
      people = data.participants;
    } else {
      const contexts = await Promise.all(projects.map((p) => api<Entry>(`/projects/${p.id}`)));
      if (me?.id !== actorId || project !== selectedProject) return;
      projectPeople = Object.fromEntries(contexts.map((c) => [c.project.id, c.participants]));
      people = [
        ...new Map(contexts.flatMap((c) => c.participants).map((p: Person) => [p.id, p])).values(),
      ] as Person[];
    }
  }
  let loadSequence = 0;
  async function loadTasks() {
    const sequence = ++loadSequence;
    const params = new URLSearchParams({
      page: String(page),
      size: '50',
      sort,
      archived: String(showArchived),
      subscribed: String(subscribed),
    });
    if (project) params.set('project', project);
    if (query) params.set('q', query);
    if (statusFilter) params.set('status', statusFilter);
    if (priorityFilter) params.set('priority', priorityFilter);
    if (view === 'My Issues' && me) params.set('assignee', String(me.id));
    if (view === 'Approvals') params.set('approval', 'PENDING');
    const result = await api<Page<Task>>('/tasks?' + params);
    if (sequence !== loadSequence) return;
    tasks = result.items;
    total = result.total;
    selected = new Set();
    rowReview = null;
    focusIndex = 0;
  }
  async function loadNotifications() {
    if (!me) return;
    const actorId = me.id;
    const data = await api<Entry>(`/notifications?page=${notificationPage}`);
    if (me?.id !== actorId) return;
    notifications = data.items;
    notificationTotal = data.total;
    unread = data.unread;
  }
  function href(next: string, selectedProject = project, section = settingsTab) {
    return pageUrl(next, selectedProject, section);
  }
  async function loadPageData() {
    loadedPage = currentPage.url.pathname + currentPage.url.search;
    const sequence = ++routeSequence;
    clearTimeout(searchTimer);
    project = currentPage.params.projectId ?? currentPage.url.searchParams.get('project') ?? '';
    settingsTab = !owner && ['People', 'Audit log'].includes(section) ? 'Account' : section;
    page = 0;
    showArchived = false;
    statusFilter = '';
    priorityFilter = '';
    query = '';
    subscribed = false;
    selected = new Set();
    loadSequence++;
    tasks = [];
    total = 0;
    detailSequence++;
    detail = null;
    editing = false;
    error = '';
    await loadPeople();
    if (sequence !== routeSequence) return;
    if (view === 'Settings') await loadSettings();
    else if (view === 'Inbox') await loadNotifications();
    else if (['All Issues', 'My Issues', 'Approvals', 'Board'].includes(view)) await loadTasks();
    if (sequence !== routeSequence) return;
    await syncTaskFragment();
  }
  let routeSequence = 0;
  let loadedPage = '';
  async function syncTaskFragment() {
    const id = new URLSearchParams(currentPage.url.hash.slice(1)).get('task');
    if (id && id !== task?.identifier) await openTask(id);
    else if (!id && detail) closeTask();
  }
  afterNavigate(() => {
    if (!me || loading) return;
    const url = currentPage.url;
    const work = loadedPage !== url.pathname + url.search ? loadPageData : syncTaskFragment;
    void work().catch((failure) => { error = (failure as Error).message; });
  });
  async function navigate(next: string) {
    await goto(href(next));
  }
  async function changeProject() {
    await goto(href(view === 'Project settings' ? 'All Issues' : view));
  }
  function projectHref(id: string) {
    return href(['Settings', 'Projects', 'Inbox', 'Project settings'].includes(view) ? 'All Issues' : view, id);
  }
  async function projectSettingsUpdated() {
    projects = await api<Project[]>('/projects');
    await loadPeople();
  }
  async function acceptInvitation() {
    await run(async () => {
      const result = await api<Entry>('/invitations/accept', 'POST', { token: invitationToken });
      invitationToken = '';
      inviteSignIn = false;
      await goto(href('All Issues', String(result.project_id ?? '')), { replaceState: true });
      await initialize();
      notify('Invitation accepted');
    });
  }
  let searchTimer: ReturnType<typeof setTimeout>;
  function search() {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => {
      page = 0;
      void run(loadTasks);
    }, 250);
  }
  let detailSequence = 0;
  async function openTask(id: string) {
    const sequence = ++detailSequence;
    const actorId = me?.id;
    error = '';
    revisionPreview = null;
    try {
      const data = await api<Entry>(`/tasks/${encodeURIComponent(id)}`);
      if (me?.id !== actorId || sequence !== detailSequence) return;
      detail = data;
      comments = [];
      activity = [];
      editing = false;
      preview = false;
      feedback = '';
      comment = '';
      commentPage = 0;
      activityPage = 0;
      const context = await api<Entry>(`/projects/${data.task.projectId}`);
      if (me?.id !== actorId || sequence !== detailSequence) return;
      projectPeople[data.task.projectId] = context.participants;
      people = context.participants;
      await loadDiscussion();
      if (sequence !== detailSequence) return;
      replaceState(`${currentPage.url.pathname}${currentPage.url.search}#task=${encodeURIComponent(id)}`, currentPage.state);
    } catch (e) {
      error = (e as Error).message;
    }
  }
  async function focusSearch() {
    if (!['All Issues', 'My Issues', 'Approvals', 'Board'].includes(view))
      await navigate('All Issues');
    await tick();
    searchInput?.focus();
  }
  function closeTask() {
    detailSequence++;
    detail = null;
    editing = false;
    replaceState(currentPage.url.pathname + currentPage.url.search, currentPage.state);
    void loadPeople().catch((e) => (error = e.message));
  }
  async function loadDiscussion() {
    if (!task) return;
    const id = task.identifier;
    const [c, a] = await Promise.all([
      api<Page<Entry>>(`/tasks/${id}/comments?page=${commentPage}`),
      api<Page<Entry>>(`/tasks/${id}/activity?page=${activityPage}`),
    ]);
    if (task?.identifier !== id) return;
    comments = c.items;
    commentTotal = c.total;
    activity = a.items;
    activityTotal = a.total;
  }
  async function refreshTask() {
    if (task) detail = await api(`/tasks/${task.identifier}`);
    await Promise.all([loadDiscussion(), loadTasks(), loadNotifications()]);
  }
  function editTask() {
    if (!task) return;
    draft = {
      title: task.title,
      description: task.description,
      type: task.type,
      priority: task.priority,
      category: task.category,
      labels: labels(task.labels).join(', '),
    };
    editing = true;
    preview = false;
  }
  async function saveTask(event: SubmitEvent) {
    event.preventDefault();
    await run(async () => {
      await api(`/tasks/${task!.identifier}`, 'PUT', {
        version: task!.version,
        task: input(draft),
      });
      editing = false;
      await refreshTask();
    });
  }
  async function taskAction(action: string, body: Entry) {
    await run(async () => {
      const identifier = task!.identifier;
      try {
        await api(`/tasks/${identifier}/${action}`, 'POST', {
          version: task!.version,
          ...body,
        });
        await refreshTask();
      } catch (failure) {
        detail = await api(`/tasks/${identifier}`);
        throw failure;
      }
    });
  }
  async function moveTask(item: Task, status: string) {
    await run(async () => {
      await api(`/tasks/${item.identifier}/status`, 'POST', { version: item.version, status });
      await loadTasks();
      if (task?.identifier === item.identifier) await refreshTask();
    });
  }
  async function reviewRow(item: Task, decision: string) {
    await run(async () => {
      try {
        await api(`/tasks/${item.identifier}/approval`, 'POST', {
          version: item.version, decision, comment: rowFeedback,
        });
      } catch (failure) {
        await loadTasks();
        throw failure;
      }
      rowReview = null;
      rowFeedback = '';
      await Promise.all([loadTasks(), loadNotifications()]);
      if (task?.identifier === item.identifier) await refreshTask();
      notify(decision === 'APPROVED' ? 'Proposal approved' : decision === 'REJECTED' ? 'Proposal rejected' : 'Changes requested');
    });
  }
  async function followRow(item: Task) {
    await run(async () => {
      await api(`/tasks/${item.identifier}/subscription`, 'POST', { subscribed: !item.subscribed });
      await loadTasks();
      if (task?.identifier === item.identifier) detail = await api(`/tasks/${item.identifier}`);
      notify(item.subscribed ? 'Issue notifications off' : 'Issue notifications on');
    });
  }
  async function startRowReview(item: Task, decision: string) {
    rowReview = { identifier: item.identifier, decision };
    rowFeedback = '';
    await tick();
    document.getElementById(`feedback-${item.id}`)?.focus();
  }
  function openCreate() {
    createProject = project || String(projects[0]?.id ?? '');
    newTask = {
      title: '',
      description: '',
      type: 'FEATURE',
      priority: 'MEDIUM',
      category: '',
      labels: '',
    };
    createOpen = true;
  }
  async function create(event: SubmitEvent) {
    event.preventDefault();
    await run(async () => {
      const created = await api<Task>('/tasks', 'POST', {
        project: Number(createProject),
        task: input(newTask),
      });
      createOpen = false;
      await loadTasks();
      await openTask(created.identifier);
    });
  }
  async function sendComment(event: SubmitEvent) {
    event.preventDefault();
    await run(async () => {
      await api(`/tasks/${task!.identifier}/comments`, 'POST', { body: comment });
      comment = '';
      commentPage = 0;
      await refreshTask();
    });
  }
  async function upload(event: Event) {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (!file) return;
    await run(async () => {
      const body = new FormData();
      body.append('file', file);
      await api(`/tasks/${task!.identifier}/attachments`, 'POST', body);
      await refreshTask();
    });
    (event.target as HTMLInputElement).value = '';
  }
  async function addLink(event: SubmitEvent) {
    event.preventDefault();
    await run(async () => {
      await api(`/tasks/${task!.identifier}/links`, 'POST', { kind: linkKind, url: linkUrl });
      linkUrl = '';
      await refreshTask();
    });
  }
  function toggleSelect(id: string) {
    const next = new Set(selected);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    selected = next;
  }
  async function archiveRow(item: Task) {
    await run(async () => {
      await api(`/tasks/${item.identifier}/archive`, 'POST', {
        version: item.version, archived: !item.archived,
      });
      selected = new Set([...selected].filter((id) => id !== item.identifier));
      await loadTasks();
      notify(item.archived ? 'Issue restored' : 'Issue archived');
    });
  }
  async function bulkArchive() {
    await run(async () => {
      for (const item of tasks.filter((t) => selected.has(t.identifier)))
        await api(`/tasks/${item.identifier}/archive`, 'POST', {
          version: item.version,
          archived: !showArchived,
        });
      await loadTasks();
    });
  }
  async function loadSettings() {
    if (!owner) { tokens = await api<Entry[]>('/tokens'); return; }
    [allUsers, tokens, audit] = await Promise.all([
      api<Person[]>('/users'),
      api<Entry[]>('/tokens'),
      api<Entry[]>(`/audit?page=${auditPage}`),
    ]);
  }
  async function createUser(event: SubmitEvent) {
    event.preventDefault();
    await run(async () => {
      await api('/users', 'POST', { ...userForm, account: userForm.account ? Number(userForm.account) : null });
      userForm = { username: '', displayName: '', kind: 'DEVELOPER', password: '', account: '' };
      await loadSettings();
      notify('Participant created');
    });
  }
  function selectedMcpProjects(): number[] {
    return mcpProjects ?? (project ? [Number(project)] : projects[0] ? [projects[0].id] : []);
  }
  async function updateMcpProjects(id: number, selected: number[]) {
    await run(async () => {
      await api(`/mcp-connections/${id}/projects`, 'PUT', { projects: selected });
      await loadSettings();
    });
  }
  async function connectMcp(account: number, name: string) {
    await run(async () => {
      const result = await api<Entry>('/mcp-connections', 'POST', { account, projects: selectedMcpProjects() });
      freshToken = result.token;
      mcpAccountName = name;
      await loadSettings();
    });
  }
  function projectEditor(item?: Project) {
    projectEditId = item?.id ?? null;
    projectForm = item
      ? {
          key: item.key,
          name: item.name,
          description: item.description,
          repository: item.repository,
        }
      : { key: '', name: '', description: '', repository: '' };
    projectFormOpen = true;
  }
  async function saveProject(event: SubmitEvent) {
    event.preventDefault();
    await run(async () => {
      await api(
        projectEditId ? `/projects/${projectEditId}` : '/projects',
        projectEditId ? 'PUT' : 'POST',
        projectForm,
      );
      projects = await api('/projects');
      projectFormOpen = false;
      await loadPeople();
    });
  }
  async function importFile(event: Event) {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (file) {
      importSource = file.name;
      importMarkdown = await file.text();
      importPreview = null;
    }
  }
  async function previewImport(event: SubmitEvent) {
    event.preventDefault();
    await run(async () => {
      importPreview = await api(`/projects/${importProject}/import/preview`, 'POST', {
        source: importSource,
        markdown: importMarkdown,
      });
    });
  }
  async function applyImport() {
    await run(async () => {
      const data = await api<Entry>(`/projects/${importProject}/import`, 'POST', {
        source: importSource,
        markdown: importMarkdown,
      });
      importOpen = false;
      importPreview = null;
      await loadTasks();
      notify(`Imported ${data.created.length} tasks · skipped ${data.skipped} duplicates`);
    });
  }
  function trapFocus(node: HTMLElement) {
    const previous = document.activeElement as HTMLElement | null;
    const focusable = () =>
      Array.from(
        node.querySelectorAll<HTMLElement>(
          'button:not(:disabled),input:not(:disabled),select:not(:disabled),textarea:not(:disabled),a[href]',
        ),
      );
    queueMicrotask(() => focusable()[0]?.focus());
    function trap(event: KeyboardEvent) {
      if (event.key !== 'Tab') return;
      const elements = focusable();
      const first = elements[0],
        last = elements.at(-1);
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last?.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first?.focus();
      }
    }
    node.addEventListener('keydown', trap);
    return {
      destroy() {
        node.removeEventListener('keydown', trap);
        previous?.focus();
      },
    };
  }
  function keyboard(event: KeyboardEvent) {
    if (event.key === 'Escape') {
      if (rowReview) { rowReview = null; return; }
      createOpen = false;
      shortcuts = false;
      projectFormOpen = false;
      importOpen = false;
      closeTask();
      return;
    }
    if ((event.target as HTMLElement).closest('input,textarea,select,[contenteditable]')) return;
    if (event.key === '/') {
      event.preventDefault();
      void focusSearch();
    }
    if (event.key === 'c' && me) openCreate();
    if (event.key === '?') shortcuts = true;
    if (event.key === 'j' || event.key === 'ArrowDown') {
      event.preventDefault();
      focusIndex = Math.min(focusIndex + 1, tasks.length - 1);
      document.getElementById(`row-${focusIndex}`)?.scrollIntoView({ block: 'nearest' });
    }
    if (event.key === 'k' || event.key === 'ArrowUp') {
      event.preventDefault();
      focusIndex = Math.max(focusIndex - 1, 0);
      document.getElementById(`row-${focusIndex}`)?.scrollIntoView({ block: 'nearest' });
    }
    if ((event.target as HTMLElement).closest('button,a') && event.key === 'Enter') return;
    if (event.key === 'Enter' && tasks[focusIndex] && !detail)
      void openTask(tasks[focusIndex].identifier);
  }
  onMount(() => {
    invitationToken = new URLSearchParams(location.hash.slice(1)).get('invite') ?? '';
    setTheme(localStorage.getItem('forge-theme') ?? 'dark');
    let alive = true;
    initialize()
      .catch(() => {
        me = null;
      })
      .finally(() => {
        if (alive) loading = false;
      });
    const poll = setInterval(() => {
      if (me && !document.hidden) void loadNotifications().catch(() => {});
    }, 60000);
    return () => {
      alive = false;
      routeSequence++;
      detailSequence++;
      loadSequence++;
      clearInterval(poll);
      clearTimeout(searchTimer);
    };
  });
</script>

{#snippet issues()}
        <div class="filters">
          <div class="search-field">
            <span>⌕</span><input
              bind:this={searchInput}
              bind:value={query}
              oninput={search}
              placeholder="Search issues…"
              aria-label="Search issues"
            /><kbd>/</kbd>
          </div>
          <select aria-label="Project" bind:value={project} onchange={changeProject}
            ><option value="">All projects</option>{#each projects as p}<option value={String(p.id)}
                >{p.name}</option
              >{/each}</select
          >
          <select
            aria-label="Status filter"
            bind:value={statusFilter}
            onchange={() => {
              page = 0;
              void run(loadTasks);
            }}
            ><option value="">All statuses</option>{#each statuses as s}<option value={s}
                >{label(s)}</option
              >{/each}</select
          >
          <select
            aria-label="Priority filter"
            bind:value={priorityFilter}
            onchange={() => {
              page = 0;
              void run(loadTasks);
            }}
            ><option value="">All priorities</option>{#each priorities as p}<option>{p}</option
              >{/each}</select
          >
          <select aria-label="Sort" bind:value={sort} onchange={() => run(loadTasks)}
            ><option value="updated">Updated</option><option value="created">Newest</option><option
              value="priority">Priority</option
            ><option value="title">Title</option></select
          >
          <label class="check-filter"
            ><input
              type="checkbox"
              bind:checked={subscribed}
              onchange={() => {
                page = 0;
                void run(loadTasks);
              }}
            />Notifications</label
          ><label class="check-filter"
            ><input
              type="checkbox"
              bind:checked={showArchived}
              onchange={() => {
                page = 0;
                void run(loadTasks);
              }}
            />Archived</label
          >
        </div>
        {#if selected.size && owner}<div class="bulk-bar">
            {selected.size} selected<button disabled={busy} onclick={bulkArchive}
              >{showArchived ? 'Restore' : 'Archive'} selected</button
            ><button onclick={() => (selected = new Set())}>Clear</button>
          </div>{/if}
        {#if view === 'Board'}
          <div class="board">
            {#each statuses as status}<section
                class="board-column"
                ondragover={(e) => e.preventDefault()}
                ondrop={(e) => {
                  e.preventDefault();
                  const id = e.dataTransfer?.getData('text/plain');
                  const item = tasks.find((t) => t.identifier === id);
                  if (item && item.status !== status) void moveTask(item, status);
                }}
                aria-label={label(status)}
              >
                <h2>
                  <span class="status-dot" style:background={colors[status]}></span>{label(
                    status,
                  )}<span>{tasks.filter((t) => t.status === status).length}</span>
                </h2>
                {#each tasks.filter((t) => t.status === status) as item}<button
                    class="board-card"
                    draggable={!busy}
                    ondragstart={(e) => e.dataTransfer?.setData('text/plain', item.identifier)}
                    onclick={() => openTask(item.identifier)}
                    ><div class="card-meta">
                      <span>{item.identifier}</span><span
                        class="priority"
                        data-priority={item.priority}
                        title={`Priority: ${label(item.priority)}`}
                        aria-label={`Priority: ${label(item.priority)}`}></span
                      >
                    </div>
                    <strong>{item.title}</strong>
                    <div class="card-footer">
                      {#if item.approval === 'REJECTED'}<span class="rejected-tag">● Rejected</span>{:else if item.approval === 'PENDING'}<span class="approval-tag">◇ Pending</span
                        >{:else}<span class="muted">{label(item.type)}</span>{/if}<span
                        class="avatar tiny"
                        title={name(item.assigneeId)}
                        >{item.assigneeId ? name(item.assigneeId).slice(0, 2) : '–'}</span
                      >
                    </div></button
                  >{/each}
              </section>{/each}
          </div>
        {:else}
          <div class="issue-table compact-issues">
            <div class="table-head">
              <span></span><span>Pri</span><span>Issue</span><span>Title</span>
              <span>Approval</span><span>Status</span><span>Author</span><span>Assignee</span>
              <span>{view === 'Approvals' ? 'Submitted' : 'Updated'}</span><span>Actions</span>
            </div>
            {#each tasks as item, i (item.identifier)}
              <div class="issue-row" class:done={item.status === 'DONE'} class:focused={focusIndex === i} class:selected={selected.has(item.identifier)} id={`row-${i}`}>
                <div class="select-cell"><input type="checkbox" aria-label={`Select ${item.identifier}`} checked={selected.has(item.identifier)} onchange={() => toggleSelect(item.identifier)} /></div>
                <button class="row-open" onclick={() => { focusIndex = i; void openTask(item.identifier); }} aria-label={`Open ${item.identifier}: ${item.title}, priority ${label(item.priority)}`}>
                  <span class="priority" data-priority={item.priority} title={`Priority: ${label(item.priority)}`}></span>
                  <span class="issue-id">{item.identifier}</span>
                  <span class="issue-title" title={item.title}><strong>{item.title}</strong>
                    {#each labels(item.labels).slice(0, 1) as tag}<span class="tag">{tag}</span>{/each}
                  </span>
                  <span class="approval-cell"><span class="approval-tag" data-approval={item.approval}>{#if item.approval === 'PENDING'}<span aria-hidden="true">◇</span>{' '}{:else if item.approval === 'REJECTED'}<span aria-hidden="true">●</span>{' '}{/if}{label(item.approval)}</span></span>
                  <span class="status-cell"><span class="status-dot" style:background={colors[item.status]}></span><span>{label(item.status)}</span></span>
                  <span class="person-cell creator-cell" title={`Author: ${name(item.creatorId)}`}><span class="avatar tiny">{name(item.creatorId).slice(0, 2)}</span><span>{name(item.creatorId)}</span></span>
                  <span class="row-date">{date(view === 'Approvals' ? item.createdAt : item.updatedAt)}</span>
                </button>
                <div class="person-cell row-assignee">
                  <select aria-label={`Assign ${item.identifier}`} title={`Assignee: ${name(item.assigneeId)}`}
                    disabled={busy || !canAssignRow(item)} value={item.assigneeId == null ? '' : String(item.assigneeId)}
                    onchange={(event) => assignRow(item, event.currentTarget.value)}>
                    <option value="">Unassigned</option>
                    {#each assignablePeople(item.projectId).filter((p) => managesProject(item.projectId) || p.id === me?.id || p.id === item.assigneeId) as person}
                      <option value={String(person.id)}>{person.display_name}{person.kind === 'AGENT' ? ' · Agent' : ''}</option>
                    {/each}
                  </select>
                </div>
                <div class="row-actions">
                  {#if owner && item.approval === 'APPROVED' && item.status !== 'DONE' && !item.archived}
                    <button class="quick-done" disabled={busy} aria-label={`Mark ${item.identifier} done`} onclick={() => moveTask(item, 'DONE')}>✓ Done</button>
                  {/if}
                  {#if managesProject(item.projectId)}
                    <button disabled={busy} title={item.archived ? 'Restore issue' : 'Archive issue'} aria-label={`${item.archived ? 'Restore' : 'Archive'} ${item.identifier}`} onclick={() => archiveRow(item)}>
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true"><path d="M4 9v11h16V9M3 4h18v5H3z"/>{#if item.archived}<path d="m9 14 3-3 3 3m-3-3v6"/>{:else}<path d="M9 13h6"/>{/if}</svg>
                    </button>
                  {/if}

                  {#if item.approval === 'PENDING' && owner && !item.archived}
                    <button class="quick-approve" disabled={busy} title={`Approve ${item.identifier}`} aria-label={`Approve ${item.identifier}`} onclick={() => { rowFeedback = ''; void reviewRow(item, 'APPROVED'); }}>✓ <span>Approve</span></button>
                    <button disabled={busy} title={`Request changes to ${item.identifier}`} aria-label={`Request changes to ${item.identifier}`} onclick={() => startRowReview(item, 'CHANGES_REQUESTED')}>↺</button>
                    <button class="danger-text" disabled={busy} title={`Reject ${item.identifier}`} aria-label={`Reject ${item.identifier}`} onclick={() => startRowReview(item, 'REJECTED')}>×</button>
                  {/if}
                  <button class:following={item.subscribed} disabled={busy} aria-pressed={item.subscribed} title={item.subscribed ? 'Stop notifications for this issue' : 'Get progress and completion notifications'} aria-label={`${item.subscribed ? 'Stop notifications for' : 'Notify me about'} ${item.identifier}`} onclick={() => followRow(item)}>{item.subscribed ? '◉' : '◎'}</button>
                </div>
              </div>
              {#if rowReview?.identifier === item.identifier}
                <form class="row-review" onsubmit={(event) => { event.preventDefault(); void reviewRow(item, rowReview!.decision); }}>
                  <label for={`feedback-${item.id}`}>{rowReview.decision === 'REJECTED' ? 'Reject proposal' : 'Request changes'} · {item.identifier}</label>
                  <input id={`feedback-${item.id}`} bind:value={rowFeedback} maxlength="10000" placeholder="Feedback for the author (optional)" disabled={busy} />
                  <button type="button" disabled={busy} onclick={() => (rowReview = null)}>Cancel</button>
                  <button class="primary" disabled={busy}>{rowReview.decision === 'REJECTED' ? 'Reject' : 'Request changes'}</button>
                </form>
              {/if}
            {/each}
          </div>
        {/if}
        {#if !tasks.length}<div class="empty">
            <div class="empty-symbol">{view === 'Approvals' ? '◇' : '☷'}</div>
            <h2>{view === 'Approvals' ? 'No proposals waiting' : 'No issues here yet'}</h2>
            <p>
              {query || statusFilter || priorityFilter
                ? 'Try adjusting your search or filters.'
                : view === 'Approvals'
                  ? 'New proposals will appear here for review.'
                  : 'Create an issue to give your team a clear next step.'}
            </p>
            {#if view !== 'Approvals' && projects.length}<button onclick={openCreate}
                >+ Create issue</button
              >{/if}
          </div>{/if}
        <footer class="list-footer">
          <span
            >{total
              ? `${page * 50 + 1}–${Math.min((page + 1) * 50, total)} of ${total} issues`
              : '0 issues'}{view === 'Board' ? ' · Board shows this page only' : ''}</span
          >
          <div>
            <button
              disabled={page === 0 || busy}
              onclick={() => {
                page--;
                void run(loadTasks);
              }}>← Previous</button
            ><button
              disabled={(page + 1) * 50 >= total || busy}
              onclick={() => {
                page++;
                void run(loadTasks);
              }}>Next →</button
            >
          </div>
          <span class="key-hint"><kbd>J</kbd><kbd>K</kbd> navigate <kbd>↵</kbd> open</span>
        </footer>
{/snippet}

{#snippet inbox()}
        <div class="inbox-list">
        <div class="section-toolbar inbox-toolbar">
          <span>{unread} unread notifications</span><button
            disabled={!unread || busy}
            onclick={() =>
              run(async () => {
                await api('/notifications/read-all', 'POST');
                await loadNotifications();
              })}>Mark all as read</button
          >
        </div>
        <div class="notifications compact-inbox">
          <div class="inbox-head" aria-hidden="true"><span></span><span>Issue</span><span>Title</span><span>Update</span><span>Date</span></div>
          {#each notifications as n}<button
              class="notification"
              class:unread={n.unread}
              aria-label={`${n.message}: ${n.title}`}
              onclick={() =>
                run(async () => {
                  await api(`/notifications/${n.id}/read`, 'POST');
                  await loadNotifications();
                  await openTask(n.identifier);
                })}
              ><span class="notification-dot"></span>
              <span class="inbox-identifier">{n.identifier}</span>
              <strong class="inbox-title">{n.title}</strong>
              <span class="inbox-event">{n.message.replace(n.identifier + ' · ', '')}</span>
              <time>{date(n.created_at)}</time></button
            >{/each}
        </div>
        {#if !notifications.length}<div class="empty">
            <div class="empty-symbol">◫</div>
            <h2>You're all caught up</h2>
            <p>Turn on issue notifications to receive updates here.</p>
          </div>{/if}
        <div class="pagination inbox-pagination">
          <button
            disabled={!notificationPage}
            onclick={() => {
              notificationPage--;
              void run(loadNotifications);
            }}>← Previous</button
          ><span>{notificationTotal} notifications</span><button
            disabled={(notificationPage + 1) * 40 >= notificationTotal}
            onclick={() => {
              notificationPage++;
              void run(loadNotifications);
            }}>Next →</button
          >
        </div>
        </div>
{/snippet}

{#snippet projectsPage()}
        <div class="section-toolbar">
          <span>{projects.length} projects</span>{#if owner}<div>
              <button
                onclick={() => {
                  importProject = project || String(projects[0]?.id ?? '');
                  importPreview = null;
                  importOpen = true;
                }}
                disabled={!projects.length}>Import TODO.md</button
              ><button class="primary" onclick={() => projectEditor()}>+ New project</button>
            </div>{/if}
        </div>
        <div class="project-list">
          {#each projects as p}<article>
              <span class="project-icon">{p.key.slice(0, 1)}</span>
              <div class="grow">
                <h2>
                  <a class="text-button" href={href('All Issues', String(p.id))}>{p.name}</a
                  ><span class="tag">{p.key}</span>
                </h2>
                <p>{p.description || 'No project description yet.'}</p>
                {#if p.repository}<a href={p.repository} target="_blank" rel="noreferrer"
                    >{p.repository.replace('https://github.com/', '')} ↗</a
                  >{/if}
              </div>
              {#if owner}<a class="project-settings-link" href={href('Project settings', String(p.id))}>Project settings</a
                >{/if}
            </article>{/each}
        </div>
        {#if !projects.length}<div class="empty">
            <div class="empty-symbol">▣</div>
            <h2>A home for your team's work</h2>
            <p>
              {owner
                ? 'Create your first project, then add people and proposals.'
                : 'Ask your workspace owner for project access.'}
            </p>
            {#if owner}<button class="primary" onclick={() => projectEditor()}
                >Create project</button
              >{/if}
          </div>{/if}
{/snippet}

{#snippet projectSettings()}
{#if currentProject && owner}
        {#key currentProject.id}<ProjectSettings
            project={currentProject}
            onUpdated={projectSettingsUpdated}
          />{/key}
{/if}
{/snippet}

{#snippet settings()}
        <div class="settings-tabs">
          {#each owner ? ['Account', 'People', 'MCP connections', 'Audit log'] : ['Account', 'MCP connections'] as section}<a href={href('Settings', project, section)}
              class:active={settingsTab === section}
              aria-current={settingsTab === section ? 'page' : undefined}>{section}</a
            >{/each}
        </div>
        <div class="settings-content">
          {#if freshToken}
            <div class="token-reveal mcp-result">
              <strong>MCP · {mcpAccountName}</strong>
              <p class="muted">Copy this token now. It is shown only once.</p>
              <label>Server URL<input readonly value={location.origin + '/mcp'} /></label>
              <label>Bearer token<textarea readonly value={freshToken} aria-label="MCP bearer token"></textarea></label>
              <div><button onclick={() => run(async () => { await navigator.clipboard.writeText(freshToken); notify('Token copied'); })}>Copy token</button><button class="text-button" onclick={() => freshToken = ''}>Done</button></div>
            </div>
          {/if}
          {#snippet projectTags(ids: number[], change: (ids: number[]) => void, locked = false)}
            <div class="mcp-project-tags" aria-label="Allowed projects">
              {#each ids as id}
                <span class="mcp-project-tag">{projects.find(p => p.id === id)?.name ?? 'Project ' + id}
                  <button class="tag-remove" aria-label={'Remove project ' + (projects.find(p => p.id === id)?.name ?? id)} disabled={busy || locked} onclick={() => change(ids.filter(value => value !== id))}>×</button>
                </span>
              {/each}
              {#if !locked && projects.some(p => !ids.includes(p.id))}
                <details class="project-add"><summary aria-label="Add project">+</summary>
                  <div class="project-add-menu">{#each projects.filter(p => !ids.includes(p.id)) as p}<button disabled={busy} onclick={() => change([...ids, p.id])}>+ {p.name}</button>{/each}</div>
                </details>
              {/if}
              {#if !ids.length}<span class="muted">No project access</span>{/if}
            </div>
          {/snippet}
          {#snippet mcpProjectPicker()}
            {@render projectTags(selectedMcpProjects(), ids => mcpProjects = ids)}
          {/snippet}
          {#if settingsTab === 'Account'}<section>
              <div class="mcp-toolbar"><h2>MCP access</h2>{@render mcpProjectPicker()}<button disabled={busy || !selectedMcpProjects().length} onclick={() => me && connectMcp(me.id, me.username)}>Connect MCP</button></div>
              <p class="muted">Connect your coding assistant with your project permissions.</p>
            </section><section>
              <h2>Appearance</h2>
              <p class="muted">Choose how Forge looks on this device.</p>
              <div class="theme-options">
                <button class:active={theme === 'dark'} onclick={() => setTheme('dark')}
                  >◐ Dark</button
                ><button class:active={theme === 'light'} onclick={() => setTheme('light')}
                  >☀ Light</button
                >
              </div>
            </section>
            <section>
              <h2>Change password</h2>
              <form
                class="settings-form"
                onsubmit={(e) => {
                  e.preventDefault();
                  void run(async () => {
                    await api('/password', 'POST', passwordForm);
                    passwordForm = { current: '', replacement: '' };
                    notify('Password updated');
                  });
                }}
              >
                <label
                  >Current password<input
                    type="password"
                    bind:value={passwordForm.current}
                    required
                    autocomplete="current-password"
                  /></label
                ><label
                  >New password<input
                    type="password"
                    bind:value={passwordForm.replacement}
                    required
                    minlength="12"
                    maxlength="72"
                    autocomplete="new-password"
                  /></label
                ><button disabled={busy}>Update password</button>
              </form>
            </section>
          {:else if settingsTab === 'People'}<section>
              <div class="mcp-toolbar"><h2>People</h2>{@render mcpProjectPicker()}</div>
              <div class="member-list">
                {#each allUsers.filter(u => u.kind !== 'AGENT') as user}<div>
                    <span class="avatar">{user.display_name.slice(0, 2)}</span><strong
                      >{user.display_name}</strong
                    ><span class="muted">@{user.username}</span><span class="tag"
                      >{label(user.kind)}</span
                    ><button class="connect-mcp" disabled={busy || !user.enabled || !selectedMcpProjects().length} onclick={() => connectMcp(user.id, user.display_name)}>Connect MCP</button><button
                      disabled={busy || user.id === me?.id}
                      onclick={() =>
                        run(async () => {
                          await api(`/users/${user.id}`, 'PUT', { enabled: !user.enabled });
                          await loadSettings();
                        })}>{user.enabled ? 'Disable' : 'Enable'}</button
                    >
                  </div>{/each}
              </div>
            </section>
            <section>
              <details class="add-person"><summary>Add person</summary>
              <form class="settings-form participant-form" onsubmit={createUser}>
                <label
                  >Username<input
                    bind:value={userForm.username}
                    pattern={'[A-Za-z0-9_.-]{1,40}'}
                    required
                  /></label
                ><label
                  >Display name<input
                    bind:value={userForm.displayName}
                    maxlength="100"
                    required
                  /></label
                ><label
                  >Role<select bind:value={userForm.kind}
                    ><option value="DEVELOPER">Developer</option><option value="OWNER"
                      >Owner / Reviewer</option
                    ></select
                  ></label
                >{#if userForm.kind !== 'AGENT'}<label
                    >Initial password<input
                      type="password"
                      bind:value={userForm.password}
                      minlength="12"
                      maxlength="72"
                      required
                      autocomplete="new-password"
                    /></label
                  >{/if}<button disabled={busy}>Create participant</button>
              </form></details>
            </section>
          {:else if settingsTab === 'MCP connections'}<section>
              <h2>MCP connections</h2>
              <p class="muted">Connect MCP from your Account or the People list. Access is limited by the linked account and project.</p>
              <div class="member-list">
                {#each tokens as token}<div>
                    <strong>{token.account_name ?? token.name}</strong>
                    {@render projectTags((token.projects ?? []).map((p: Entry) => Number(p.id)), ids => updateMcpProjects(Number(token.id), ids), !!token.revoked)}<small>{token.expires_at ? date(token.expires_at) : 'No expiry'}</small><button
                      disabled={token.revoked || busy}
                      onclick={() =>
                        run(async () => {
                          await api(`/tokens/${token.id}`, 'DELETE');
                          await loadSettings();
                        })}>{token.revoked ? 'Revoked' : 'Revoke'}</button
                    >
                  </div>{/each}
              </div>
            </section>
          {:else if settingsTab === 'Audit log'}<section>
              <h2>Audit history</h2>
              <div class="audit-list">
                {#each audit as event}<div>
                    <time>{new Date(event.created_at).toLocaleString()}</time><strong
                      >{event.username}</strong
                    ><span>{event.action}</span><code>{event.target}</code>
                  </div>{/each}
              </div>
              <div class="pagination">
                <button
                  disabled={!auditPage}
                  onclick={() => {
                    auditPage--;
                    void run(loadSettings);
                  }}>Previous</button
                ><span>Page {auditPage + 1}</span><button
                  disabled={audit.length < 50}
                  onclick={() => {
                    auditPage++;
                    void run(loadSettings);
                  }}>Next</button
                >
              </div>
            </section>{/if}
        </div>
{/snippet}

<svelte:head>
  <title>{view === 'Settings' ? settingsTab + ' · Settings' : view} · Forge</title>
</svelte:head>

<svelte:window onkeydown={keyboard} />

{#if loading}<div class="startup">
    <div class="brand-mark">f</div>
    <p>Opening your workspace…</p>
  </div>
{:else if !me}
  <main class="login">
    <form onsubmit={login}>
      <div class="brand-mark">f</div>
      <h1>{invitationToken && !inviteSignIn ? 'Join Forge' : 'Welcome to Forge'}</h1>
      <p class="muted">A clear path from proposal to shipped.</p>
      {#if invitationToken && !inviteSignIn}<label
          >Display name<input bind:value={registrationName} maxlength="100" required /></label
        >{/if}
      <label
        >Username<input
          bind:value={username}
          autocomplete="username"
          pattern={'[A-Za-z0-9_.-]{1,40}'}
          required
        /></label
      ><label
        >Password<input
          bind:value={password}
          type="password"
          autocomplete={invitationToken && !inviteSignIn ? 'new-password' : 'current-password'}
          minlength={invitationToken && !inviteSignIn ? 12 : undefined}
          required
        /></label
      >
      {#if invitationToken && !inviteSignIn}<label
          >Confirm password<input
            type="password"
            bind:value={confirmation}
            autocomplete="new-password"
            required
          /></label
        >{/if}
      {#if error}<div class="error">{error}</div>{/if}<button class="primary" disabled={busy}
        >{invitationToken
          ? inviteSignIn
            ? 'Sign in & accept invitation'
            : 'Create account & join'
          : 'Sign in'} <span>→</span></button
      >
      <p class="fine">
        {invitationToken
          ? 'Single-use invitation. Project access is granted when you join.'
          : 'Ask your workspace owner for an invitation to create an account.'}
      </p>
      {#if invitationToken}<button
          type="button"
          onclick={() => {
            inviteSignIn = !inviteSignIn;
            error = '';
            password = '';
            confirmation = '';
          }}
          >{inviteSignIn
            ? 'Create a new account instead'
            : 'Already have an account? Sign in'}</button
        >
        <button
          type="button"
          onclick={() => {
            invitationToken = '';
            inviteSignIn = false;
            replaceState(currentPage.url.pathname + currentPage.url.search, currentPage.state);
            error = '';
          }}>Back to sign in</button
        >{/if}
    </form>
  </main>
{:else if invitationToken}
  <main class="login">
    <div class="invitation-card">
      <div class="brand-mark">f</div>
      <h1>Join a project</h1>
      <p class="muted">
        Accept this invitation as @{me.username}. Your role applies to the invited project only. If
        you already belong to it, your current role is kept.
      </p>
      {#if error}<div class="error" role="alert">{error}</div>{/if}
      <button class="primary" disabled={busy} onclick={acceptInvitation}>Accept invitation</button>
      <button disabled={busy} onclick={logout}>Use a different account</button>
      <button
        disabled={busy}
        onclick={() => {
          invitationToken = '';
          replaceState(currentPage.url.pathname + currentPage.url.search, currentPage.state);
          error = '';
        }}>Back to workspace</button
      >
    </div>
  </main>
{:else}
  <div class="app-shell">
    <aside class="sidebar">
      <div class="workspace">
        <div class="brand-mark">f</div>
        <strong>Forge</strong><span class="workspace-label">WORKSPACE</span>
      </div>
      <button class="search-trigger" onclick={focusSearch}
        ><span>⌕</span> Search issues <kbd>/</kbd></button
      >
      <nav>
        {#each navigation as item}<a
            href={href(item.name)}
            aria-current={view === item.name ? 'page' : undefined}
            class:active={view === item.name}
            ><span class="nav-icon">{item.icon}</span
            >{item.name}{#if item.name === 'Inbox' && unread}<span class="counter">{unread}</span
              >{/if}{#if item.name === 'Approvals'}<span class="nav-dot"></span>{/if}</a
          >{/each}
      </nav>
      <div class="sidebar-heading">
        PROJECTS {#if owner}<button
            class="icon-button"
            aria-label="Create project"
            onclick={() => projectEditor()}>+</button
          >{/if}
      </div>
      <a href={projectHref('')} class="project-nav" class:chosen={!project}
        aria-current={!project && ['All Issues', 'My Issues', 'Approvals', 'Board'].includes(view) ? 'page' : undefined}
        ><span class="project-glyph">▦</span>All projects</a>
      {#each projects as item}<div class="project-nav-row" class:chosen={project === String(item.id)}>
          <a href={projectHref(String(item.id))} class="project-nav"
            class:chosen={project === String(item.id)}
            aria-current={project === String(item.id) && ['All Issues', 'My Issues', 'Approvals', 'Board'].includes(view) ? 'page' : undefined}
            ><span class="project-glyph">{item.key.slice(0, 1)}</span>{item.name}</a>
          {#if owner}<a href={href('Project settings', String(item.id))}
              class="project-settings-button icon-button"
              class:active={project === String(item.id) && view === 'Project settings'}
              aria-current={project === String(item.id) && view === 'Project settings' ? 'page' : undefined}
              aria-label={`Settings for ${item.name}`} title={`Settings for ${item.name}`}>⚙</a>{/if}
        </div>{/each}
      <div class="sidebar-bottom">
        <a href={href('Settings')} aria-current={view === 'Settings' ? 'page' : undefined} class:active={view === 'Settings'}
          ><span class="nav-icon">⚙</span>Settings</a
        ><button onclick={() => (shortcuts = true)}
          ><span class="nav-icon">?</span>Keyboard shortcuts<kbd>?</kbd></button
        >
        <div class="profile">
          <span class="avatar">{me.username.slice(0, 2).toUpperCase()}</span>
          <div><strong>{me.username}</strong><small>{label(me.kind)}</small></div>
          <button class="icon-button" aria-label="Sign out" onclick={logout}>↪</button>
        </div>
      </div>
    </aside>
    <main class="main">
      <header class="topbar">
        <div class="breadcrumb">
          Workspace <span>/</span> <strong>{currentProject?.name ?? 'All projects'}</strong><span
            >/</span
          >{view}
        </div>
        <button class="primary small" onclick={openCreate} disabled={!projects.length}
          >+ New issue <kbd>C</kbd></button
        >
      </header>
      {#if error}<div role="alert" class="error global-error">
          <span>{error}</span><button
            class="icon-button"
            aria-label="Dismiss error"
            onclick={() => (error = '')}>×</button
          >
        </div>{/if}
      <div class="view-heading">
        <div>
          <h1>
            {view}<span class="heading-count"
              >{['All Issues', 'My Issues', 'Approvals', 'Board'].includes(view) ? total : ''}</span
            >
          </h1>
          <p>
            {view === 'Approvals'
              ? 'Good work starts with a clear decision. Review proposed changes.'
              : view === 'Inbox'
                ? 'The updates that need your attention.'
                : view === 'Projects'
                  ? 'Keep the work connected to its context.'
                  : view === 'Settings'
                    ? 'Your workspace, people and integrations.'
                    : view === 'Project settings'
                      ? 'Manage this project, its members, invitations and roles.'
                      : view === 'My Issues'
                        ? 'Your next steps, all in one place.'
                        : 'Everything your team is moving forward.'}
          </p>
        </div>
        {#if ['All Issues', 'Board'].includes(view)}<div class="view-switch">
            <a href={href('All Issues')} class:active={view === 'All Issues'}
              >☷ List</a
            ><a href={href('Board')} class:active={view === 'Board'}
              >▥ Board</a
            >
          </div>{/if}
      </div>
      {@render children()}
    </main>
  </div>

  {#if task}
    <aside class="detail-panel" aria-label={`Issue ${task.identifier}`}>
      <header class="panel-header">
        <span
          ><span class="muted">{projects.find((p) => p.id === task!.projectId)?.name}</span><span
            class="slash">/</span
          >{task.identifier}</span
        ><button class="icon-button" aria-label="Close issue" onclick={closeTask}>×</button>
      </header>
      <div class="panel-scroll">
        <div class="detail-actions">
          <span class="tag">{label(task.type)}</span><button
            disabled={busy}
            onclick={() => taskAction('subscription', { subscribed: !detail!.subscribed })}
            >{detail!.subscribed ? '◉ Notifications on' : '◎ Notify me'}</button
          >{#if canManage}<button
              disabled={busy}
              onclick={() => taskAction('archive', { archived: !task!.archived })}
              >{task.archived ? 'Restore' : 'Archive'}</button
            >{/if}{#if canEdit && !task.archived}<button onclick={editTask}>Edit</button>{/if}
        </div>
        {#if task.archived}<div class="notice">
            This issue is archived. Its identifier is preserved.
          </div>{/if}
        {#if editing}<form class="edit-form" onsubmit={saveTask}>
            <label
              >Title<input
                class="title-input"
                bind:value={draft.title}
                maxlength="240"
                required
              /></label
            ><label
              >Description<textarea
                class="description-input"
                bind:value={draft.description}
                maxlength="100000"></textarea></label
            ><button type="button" class="text-button" onclick={() => (preview = !preview)}
              >{preview ? 'Hide preview' : 'Preview Markdown'}</button
            >{#if preview}<Markdown content={draft.description} {openTask} />{/if}
            <div class="form-grid">
              <label
                >Type<select bind:value={draft.type}
                  >{#each types as t}<option value={t}>{label(t)}</option>{/each}</select
                ></label
              ><label
                >Priority<select bind:value={draft.priority}
                  >{#each priorities as p}<option value={p}>{label(p)}</option>{/each}</select
                ></label
              ><label>Category<input bind:value={draft.category} maxlength="120" /></label><label
                >Labels (comma separated)<input bind:value={draft.labels} /></label
              >
            </div>
            <p class="fine">
              Title, description, type and category changes require a new approval.
            </p>
            <div class="form-actions">
              <button type="button" onclick={() => (editing = false)}>Cancel</button><button
                class="primary"
                disabled={busy}>Save changes</button
              >
            </div>
          </form>
        {:else}<h1 class="detail-title">{task.title}</h1>
          <div class="properties">
            <span>Status</span><select
              aria-label="Issue status"
              disabled={busy || task.archived || !canWrite}
              value={task.status}
              onchange={(e) => taskAction('status', { status: e.currentTarget.value })}
              ><option value={task.status}>{label(task.status)}</option
              >{#each statuses.filter((s) => s !== task!.status) as s}<option value={s}>{label(s)}</option
                >{/each}</select
            ><span>Priority</span><strong class="priority-text" data-priority={task.priority}
              >{label(task.priority)}</strong
            ><span>Assignee</span><select
              aria-label="Issue assignee"
              disabled={busy || task.archived || !canManage}
              value={task.assigneeId == null ? '' : String(task.assigneeId)}
              onchange={(e) =>
                taskAction('assign', {
                  assignee: e.currentTarget.value ? Number(e.currentTarget.value) : null,
                })}
              ><option value="">Unassigned</option
              >{#each people.filter((p) => p.kind === 'OWNER' || ['WRITE', 'MANAGE'].includes(p.permission ?? '')) as p}<option
                  value={String(p.id)}>{p.display_name}{p.kind === 'AGENT' ? ' · Agent' : ''}</option
                >{/each}</select
            >{#if !task.assigneeId && task.approval === 'APPROVED' && canWrite}<span></span><button
                disabled={busy}
                onclick={() => taskAction('assign', { assignee: me!.id })}>Assign to me</button
              >{/if}<span>Approval</span><strong class="approval-state" data-state={task.approval}
              >◇ {label(task.approval)} <small>rev {task.revision}</small></strong
            ><span>Category</span><span>{task.category || '—'}</span><span>Labels</span>
            <div>
              {#each labels(task.labels) as tag}<span class="tag">{tag}</span>{:else}—{/each}
            </div>
            <span>Created by</span><span>{name(task.creatorId)} · {date(task.createdAt)}</span>
          </div>
          <div class="description">
            {#if task.revision > 1}<div class="revision-toolbar">
                <span class="fine">Requirements · revision {task.revision}</span><button
                  disabled={busy}
                  onclick={() =>
                    run(async () => {
                      revisionPreview = revisionPreview
                        ? null
                        : await api(
                            '/tasks/' + task!.identifier + '/revisions/' + (task!.revision - 1),
                          );
                    })}
                  >{revisionPreview
                    ? 'Hide previous revision'
                    : 'Compare previous revision'}</button
                >
              </div>
              {#if revisionPreview}<div class="previous-revision">
                  <h2>Previous revision {revisionPreview.revision}</h2>
                  <strong>{revisionPreview.title}</strong>
                  <p class="fine">
                    {label(revisionPreview.type)} · {revisionPreview.category || 'No category'}
                  </p>
                  <Markdown content={revisionPreview.description} {openTask} />
                </div>
                <h2 class="current-revision-label">Current revision</h2>{/if}{/if}<Markdown
              content={task.description || '_No description provided._'}
              {openTask}
            />
          </div>
        {/if}
        {#if task.approval === 'PENDING' && owner && !task.archived}<section class="approval-box">
            <h2>Review proposal <span class="muted">· revision {task.revision}</span></h2>
            <p>Review the scope above before allowing implementation.</p>
            <textarea
              bind:value={feedback}
              placeholder="Feedback for the author (optional)"
              maxlength="10000"
              aria-label="Approval feedback"></textarea>
            <div class="approval-buttons">
              <button
                class="approve"
                disabled={busy}
                onclick={() => taskAction('approval', { decision: 'APPROVED', comment: feedback })}
                >✓ Approve</button
              ><button
                disabled={busy}
                onclick={() =>
                  taskAction('approval', { decision: 'CHANGES_REQUESTED', comment: feedback })}
                >Request changes</button
              ><button
                class="danger-text"
                disabled={busy}
                onclick={() => taskAction('approval', { decision: 'REJECTED', comment: feedback })}
                >Reject</button
              >
            </div>
          </section>{/if}
        {#if task.approval === 'CHANGES_REQUESTED' && canEdit && !task.archived}<div class="notice">
            Review the feedback below and revise the proposal.<button
              disabled={busy}
              onclick={() => taskAction('resubmit', {})}>Resubmit for approval</button
            >
          </div>{/if}
        {#if task.status === 'IN_REVIEW' && owner && !task.archived}<section class="approval-box">
            <h2>Implementation review</h2>
            <p>A linked pull request does not complete the task. Accept the work or return it.</p>
            <div class="approval-buttons">
              <button
                class="approve"
                disabled={busy}
                onclick={() => taskAction('status', { status: 'DONE' })}>Accept & mark done</button
              ><button
                disabled={busy}
                onclick={() => taskAction('status', { status: 'IN_PROGRESS' })}
                >Request additional work</button
              >
            </div>
          </section>{/if}
        <section class="attachment-section">
          <div class="section-heading">
            <h2>Attachments <span>{detail!.attachments.length}</span></h2>
            {#if canWrite && !task.archived}<label class="upload-button"
                >+ Add image<input
                  type="file"
                  accept="image/png,image/jpeg,image/gif"
                  onchange={upload}
                  disabled={busy}
                /></label
              >{/if}
          </div>
          {#if detail!.attachments.length}<div class="images">
              {#each detail!.attachments as attachment}<a
                  href={`/api/attachments/${attachment.id}`}
                  target="_blank"
                  rel="noreferrer"
                  ><img src={`/api/attachments/${attachment.id}`} alt={attachment.filename} /><span
                    >{attachment.filename}</span
                  ></a
                >{/each}
            </div>{/if}
        </section>
        <section>
          <div class="section-heading"><h2>Links <span>{detail!.links.length}</span></h2></div>
          {#each detail!.links as link}<div class="linked-resource">
              <span class="tag">{link.kind}</span>{#if link.kind === 'RELATED'}<button
                  class="text-button"
                  onclick={() => openTask(link.url)}>{link.url} →</button
                >{:else}<a href={link.url} target="_blank" rel="noreferrer"
                  >{link.url.replace('https://github.com/', '')} ↗</a
                >{/if}
            </div>{/each}{#if canEdit && !task.archived}<form class="link-form" onsubmit={addLink}>
              <select aria-label="Link type" bind:value={linkKind}
                ><option value="PR">Pull request</option><option value="COMMIT">Commit</option
                ><option value="RELATED">Related issue</option></select
              ><input
                aria-label="Link URL or task ID"
                bind:value={linkUrl}
                placeholder={linkKind === 'RELATED' ? 'GAME-42' : 'https://github.com/…'}
                required
                maxlength="500"
              /><button disabled={busy}>Add</button>
            </form>{/if}
        </section>
        {#if detail!.approvals.length}<section class="decision-history">
            <h2>Approval history</h2>
            {#each detail!.approvals as decision}<article>
                <strong class="approval-state" data-state={decision.decision}
                  >{label(decision.decision)}</strong
                ><small
                  >{decision.reviewer} · rev {decision.revision} · {date(
                    decision.created_at,
                  )}</small
                >{#if decision.comment}<Markdown content={decision.comment} {openTask} />{/if}
              </article>{/each}
          </section>{/if}
        <div class="discussion-tabs">
          <button class:active={tab === 'Comments'} onclick={() => (tab = 'Comments')}
            >Comments <span>{commentTotal}</span></button
          ><button class:active={tab === 'Activity'} onclick={() => (tab = 'Activity')}
            >Activity</button
          >
        </div>
        {#if tab === 'Comments'}<div class="comments">
            {#each comments as entry}<article>
                <div class="comment-heading">
                  <span class="avatar tiny">{entry.author.slice(0, 2)}</span><strong
                    >{entry.author}</strong
                  ><time>{date(entry.created_at)}</time>
                </div>
                <Markdown content={entry.body} {openTask} />
              </article>{/each}{#if !comments.length}<p class="muted fine">
                Start the conversation. Mention teammates with @username.
              </p>{/if}
          </div>
          {#if commentTotal > 30}<div class="pagination">
              <button
                disabled={!commentPage}
                onclick={() => {
                  commentPage--;
                  void run(loadDiscussion);
                }}>Newer</button
              ><span>Page {commentPage + 1}</span><button
                disabled={(commentPage + 1) * 30 >= commentTotal}
                onclick={() => {
                  commentPage++;
                  void run(loadDiscussion);
                }}>Older</button
              >
            </div>{/if}{#if canWrite && !task.archived}<form
              class="comment-form"
              onsubmit={sendComment}
            >
              <textarea
                bind:value={comment}
                placeholder="Leave a comment…"
                required
                maxlength="20000"
                aria-label="Comment"></textarea><button
                disabled={busy || !comment.trim()}
                class="primary">Post comment</button
              >
            </form>{/if}
        {:else}<div class="activity">
            {#each activity as entry}<div>
                <span class="activity-dot"></span>
                <div>
                  <strong>{entry.author}</strong>
                  {label(entry.event)}
                  <p>{entry.detail}</p>
                </div>
                <time>{date(entry.created_at)}</time>
              </div>{/each}
          </div>
          {#if activityTotal > 30}<div class="pagination">
              <button
                disabled={!activityPage}
                onclick={() => {
                  activityPage--;
                  void run(loadDiscussion);
                }}>Newer</button
              ><span>Page {activityPage + 1}</span><button
                disabled={(activityPage + 1) * 30 >= activityTotal}
                onclick={() => {
                  activityPage++;
                  void run(loadDiscussion);
                }}>Older</button
              >
            </div>{/if}{/if}
      </div>
      <footer class="panel-footer">
        {task.identifier}
        <span>Version {task.version} · Updated {new Date(task.updatedAt).toLocaleString()}</span>
      </footer>
    </aside>
  {/if}
{/if}

{#if createOpen}<div class="modal-backdrop">
    <div
      use:trapFocus
      class="modal"
      role="dialog"
      aria-modal="true"
      aria-label="Create issue"
      tabindex="-1"
    >
      <header>
        <h2>New issue</h2>
        <button class="icon-button" aria-label="Close" onclick={() => (createOpen = false)}
          >×</button
        >
      </header>
      <form onsubmit={create}>
        <label
          >Project<select bind:value={createProject} required
            ><option value="">Select project</option>{#each projects as p}<option value={String(p.id)}
                >{p.name}</option
              >{/each}</select
          ></label
        ><label>Issue title<input
            class="title-input"
            bind:value={newTask.title}
            required
            maxlength="240"
          /></label
        ><label>Description<textarea
            class="description-input"
            bind:value={newTask.description}
            placeholder="Describe the problem, requirements and expected outcome…"
            maxlength="100000"></textarea></label>
        <div class="form-grid">
          <label
            >Type<select bind:value={newTask.type}
              >{#each types as t}<option value={t}>{label(t)}</option>{/each}</select
            ></label
          ><label
            >Priority<select bind:value={newTask.priority}
              >{#each priorities as p}<option value={p}>{label(p)}</option>{/each}</select
            ></label
          ><label>Category<input bind:value={newTask.category} maxlength="120" /></label><label
            >Labels<input bind:value={newTask.labels} placeholder="Comma separated" /></label
          >
        </div>
        <p class="fine">New issues start in Backlog and require human approval before work can begin.</p>
        <footer>
          <button type="button" onclick={() => (createOpen = false)}>Cancel</button><button
            class="primary"
            disabled={busy || !createProject}>Create issue</button
          >
        </footer>
      </form>
    </div>
  </div>{/if}
{#if projectFormOpen}<div class="modal-backdrop">
    <div
      use:trapFocus
      class="modal"
      role="dialog"
      aria-modal="true"
      aria-label="Project"
      tabindex="-1"
    >
      <header>
        <h2>{projectEditId ? 'Edit project' : 'New project'}</h2>
        <button class="icon-button" aria-label="Close" onclick={() => (projectFormOpen = false)}
          >×</button
        >
      </header>
      <form onsubmit={saveProject}>
        <div class="form-grid">
          <label>Project name<input bind:value={projectForm.name} required maxlength="100" /></label
          ><label
            >Permanent key<input
              bind:value={projectForm.key}
              disabled={!!projectEditId}
              placeholder="GAME"
              pattern={'[A-Z][A-Z0-9]{1,9}'}
              required
            /></label
          >
        </div>
        <label
          >Project context<textarea
            bind:value={projectForm.description}
            maxlength="20000"
            placeholder="Goals, conventions and useful context for your team and agents."
          ></textarea></label
        ><label
          >GitHub repository<input
            type="url"
            bind:value={projectForm.repository}
            placeholder="https://github.com/team/repository"
          /></label
        >
        <footer>
          <button type="button" onclick={() => (projectFormOpen = false)}>Cancel</button><button
            class="primary"
            disabled={busy}>Save project</button
          >
        </footer>
      </form>
    </div>
  </div>{/if}
{#if importOpen}<div class="modal-backdrop">
    <div
      use:trapFocus
      class="modal"
      role="dialog"
      aria-modal="true"
      aria-label="Import Markdown"
      tabindex="-1"
    >
      <header>
        <h2>Import Markdown tasks</h2>
        <button class="icon-button" aria-label="Close" onclick={() => (importOpen = false)}
          >×</button
        >
      </header>
      <form onsubmit={previewImport}>
        <label
          >Project<select
            bind:value={importProject}
            onchange={() => (importPreview = null)}
            required
            ><option value="">Select project</option>{#each projects as p}<option value={String(p.id)}
                >{p.name}</option
              >{/each}</select
          ></label
        ><label
          >Source name<input
            bind:value={importSource}
            oninput={() => (importPreview = null)}
            required
            maxlength="200"
          /></label
        ><label
          >Markdown file<input
            type="file"
            accept=".md,.markdown,.txt"
            onchange={importFile}
          /></label
        ><textarea
          class="description-input"
          bind:value={importMarkdown}
          oninput={() => (importPreview = null)}
          placeholder="# Category&#10;- [ ] A new task&#10;- [x] Completed task"
          required
          aria-label="Markdown source"></textarea>
        <p class="fine">
          Headings become categories. Checked tasks are imported as approved and done by you.
          Matching source, category and title are skipped on repeat imports.
        </p>
        {#if importPreview}<div class="import-preview">
            {#each importPreview as entry}<div>
                <span>{entry.duplicate ? 'Skip' : entry.completed ? 'Done' : 'New'}</span><strong
                  >{entry.title}</strong
                ><small>{entry.category}</small>
              </div>{/each}
            <p>
              {importPreview.filter((e) => !e.duplicate).length} new · {importPreview.filter(
                (e) => e.duplicate,
              ).length} duplicates
            </p>
          </div>{/if}
        <footer>
          <button type="submit" disabled={busy}>Preview import</button><button
            type="button"
            class="primary"
            disabled={busy || !importPreview?.some((e) => !e.duplicate)}
            onclick={applyImport}>Import tasks</button
          >
        </footer>
      </form>
    </div>
  </div>{/if}
{#if shortcuts}<div class="modal-backdrop">
    <div
      use:trapFocus
      class="modal shortcuts"
      role="dialog"
      aria-modal="true"
      aria-label="Keyboard shortcuts"
      tabindex="-1"
    >
      <header>
        <h2>Keyboard shortcuts</h2>
        <button class="icon-button" aria-label="Close" onclick={() => (shortcuts = false)}>×</button
        >
      </header>
      {#each [['/', 'Search issues'], ['C', 'Create issue'], ['J / ↓', 'Next issue'], ['K / ↑', 'Previous issue'], ['Enter', 'Open selected issue'], ['Esc', 'Close panel or dialog'], ['?', 'Show shortcuts']] as shortcut}<div
        >
          <span>{shortcut[1]}</span><kbd>{shortcut[0]}</kbd>
        </div>{/each}
    </div>
  </div>{/if}
{#if error && (createOpen || projectFormOpen || importOpen || task)}<div
    class="floating-error"
    role="alert"
  >
    {error}<button class="icon-button" aria-label="Dismiss" onclick={() => (error = '')}>×</button>
  </div>{/if}
{#if toast}<div class="toast" role="status">✓ {toast}</div>{/if}
