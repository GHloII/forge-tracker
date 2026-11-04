<script lang="ts">
  import { onMount, tick } from 'svelte';
  import Markdown from './Markdown.svelte';
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
  let invitationToken = $state(new URLSearchParams(location.hash.slice(1)).get('invite') ?? '');
  let registrationName = $state(''), confirmation = $state('');
  let invitations = $state<Entry[]>([]), freshInvitation = $state('');
  let username = $state(''),
    password = $state('');
  let view = $state('All Issues'),
    project = $state(''),
    projects = $state<Project[]>([]),
    people = $state<Person[]>([]);
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
  let settingsTab = $state('Account'),
    allUsers = $state<Person[]>([]),
    tokens = $state<Entry[]>([]),
    audit = $state<Entry[]>([]),
    auditPage = $state(0);
  let userForm = $state({ username: '', displayName: '', kind: 'DEVELOPER', password: '' });
  let memberUser = $state(''),
    memberPermission = $state('WRITE');
  let tokenForm = $state({ user: '', project: '', name: '', permission: 'WRITE', expiresAt: '' }),
    freshToken = $state('');
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
    theme = $state(localStorage.getItem('forge-theme') ?? 'dark'),
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
  const transitions: Record<string, string[]> = {
    BACKLOG: ['READY', 'CANCELLED'],
    READY: ['BACKLOG', 'IN_PROGRESS', 'CANCELLED'],
    IN_PROGRESS: ['READY', 'IN_REVIEW', 'CANCELLED'],
    IN_REVIEW: ['IN_PROGRESS', 'DONE', 'CANCELLED'],
    DONE: ['BACKLOG', 'READY'],
    CANCELLED: ['BACKLOG'],
  };
  let currentProject = $derived(projects.find((p) => String(p.id) === project));
  let owner = $derived(me?.kind === 'OWNER');
  let canManage = $derived(owner || people.find((p) => p.id === me?.id)?.permission === 'MANAGE');
  let canWrite = $derived(
    owner || ['WRITE', 'MANAGE'].includes(people.find((p) => p.id === me?.id)?.permission ?? ''),
  );
  let canEdit = $derived(
    canWrite && (canManage || task?.creatorId === me?.id || task?.assigneeId === me?.id),
  );

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
    return people.find((p) => p.id === id)?.display_name ?? (id ? `User ${id}` : 'Unassigned');
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
    await loadPeople();
    await Promise.all([loadTasks(), loadNotifications()]);
    const id = new URLSearchParams(location.hash.slice(1)).get('task');
    if (id) await openTask(id);
  }
  async function login(event: SubmitEvent) {
    event.preventDefault();
    await run(async () => {
      if (invitationToken) {
        if (password !== confirmation) throw new Error('Passwords do not match.');
        await api('/register', 'POST', { token: invitationToken, username, displayName: registrationName, password });
        invitationToken = '';
        confirmation = '';
        history.replaceState(null, '', location.pathname);
        notify('Account created');
      }
      await api('/login', 'POST', new URLSearchParams({ username, password }));
      password = '';
      await refreshCsrf();
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
      freshInvitation = '';
      invitations = [];
      tokens = [];
      allUsers = [];
      audit = [];
      people = [];
      projects = [];
      tasks = [];
      notifications = [];
      unread = 0;
      project = '';
      view = 'All Issues';
      settingsTab = 'Account';
      passwordForm = { current: '', replacement: '' };
      userForm = { username: '', displayName: '', kind: 'DEVELOPER', password: '' };
      selected = new Set();
      comments = [];
      activity = [];
      createOpen = false;
      projectFormOpen = false;
      importOpen = false;
      importMarkdown = '';
      importPreview = null;
      detailSequence++;
      loadSequence++;
      history.replaceState(null, '', location.pathname);
      await refreshCsrf();
    });
  }
  async function loadPeople() {
    const actorId = me?.id;
    if (project) {
      const data = await api<Entry>(`/projects/${project}`);
      if (me?.id !== actorId) return;
      people = data.participants;
    } else {
      const contexts = await Promise.all(projects.map((p) => api<Entry>(`/projects/${p.id}`)));
      if (me?.id !== actorId) return;
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
  async function navigate(next: string) {
    view = next;
    page = 0;
    showArchived = false;
    statusFilter = '';
    selected = new Set();
    await run(async () => {
      if (next === 'Settings') await loadSettings();
      else if (next === 'Inbox') await loadNotifications();
      else await loadTasks();
    });
  }
  async function changeProject() {
    page = 0;
    await run(async () => {
      await loadPeople();
      await loadTasks();
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
      people = context.participants;
      await loadDiscussion();
      if (sequence !== detailSequence) return;
      history.replaceState(null, '', `#task=${id}`);
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
    history.replaceState(null, '', location.pathname);
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
    if (!owner) return;
    [allUsers, tokens, audit, invitations] = await Promise.all([
      api<Person[]>('/users'),
      api<Entry[]>('/tokens'),
      api<Entry[]>(`/audit?page=${auditPage}`),
      api<Entry[]>('/invitations'),
    ]);
  }
  async function createUser(event: SubmitEvent) {
    event.preventDefault();
    await run(async () => {
      await api('/users', 'POST', userForm);
      userForm = { username: '', displayName: '', kind: 'DEVELOPER', password: '' };
      await loadSettings();
      notify('Participant created');
    });
  }
  async function saveMember(event: SubmitEvent) {
    event.preventDefault();
    await run(async () => {
      await api(`/projects/${project}/members`, 'POST', {
        user: Number(memberUser),
        permission: memberPermission,
      });
      await loadPeople();
      notify('Project access updated');
    });
  }
  async function createToken(event: SubmitEvent) {
    event.preventDefault();
    await run(async () => {
      const data = await api<Entry>('/tokens', 'POST', {
        ...tokenForm,
        user: Number(tokenForm.user),
        project: Number(tokenForm.project),
        expiresAt: tokenForm.expiresAt ? new Date(tokenForm.expiresAt).toISOString() : null,
      });
      freshToken = data.token;
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
    if (event.key === 'Enter' && tasks[focusIndex] && !detail)
      void openTask(tasks[focusIndex].identifier);
  }
  onMount(() => {
    setTheme(theme);
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
      clearInterval(poll);
      clearTimeout(searchTimer);
    };
  });
</script>

<svelte:window onkeydown={keyboard} />

{#if loading}<div class="startup">
    <div class="brand-mark">f</div>
    <p>Opening your workspace…</p>
  </div>
{:else if !me}
  <main class="login">
    <form onsubmit={login}>
      <div class="brand-mark">f</div>
      <h1>{invitationToken ? 'Join Forge' : 'Welcome to Forge'}</h1>
      <p class="muted">A clear path from proposal to shipped.</p>
      {#if invitationToken}<label>Display name<input bind:value={registrationName} maxlength="100" required /></label>{/if}
      <label>Username<input bind:value={username} autocomplete="username" pattern={'[A-Za-z0-9_.-]{1,40}'} required /></label><label
        >Password<input
          bind:value={password}
          type="password"
          autocomplete={invitationToken ? 'new-password' : 'current-password'}
          minlength={invitationToken ? 12 : undefined}
          required
        /></label
      >
      {#if invitationToken}<label>Confirm password<input type="password" bind:value={confirmation} autocomplete="new-password" required /></label>{/if}
      {#if error}<div class="error">{error}</div>{/if}<button class="primary" disabled={busy}
        >{invitationToken ? 'Create account' : 'Sign in'} <span>→</span></button
      >
      <p class="fine">{invitationToken ? 'Single-use invitation. Your owner will grant project access.' : 'Ask your workspace owner for an invitation to create an account.'}</p>
      {#if invitationToken}<button type="button" onclick={() => { invitationToken = ''; history.replaceState(null, '', location.pathname); error = ''; }}>Back to sign in</button>{/if}
    </form>
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
        {#each navigation as item}<button
            class:active={view === item.name}
            onclick={() => navigate(item.name)}
            ><span class="nav-icon">{item.icon}</span
            >{item.name}{#if item.name === 'Inbox' && unread}<span class="counter">{unread}</span
              >{/if}{#if item.name === 'Approvals'}<span class="nav-dot"></span>{/if}</button
          >{/each}
      </nav>
      <div class="sidebar-heading">
        PROJECTS {#if owner}<button
            class="icon-button"
            aria-label="Create project"
            onclick={() => projectEditor()}>+</button
          >{/if}
      </div>
      <button
        class="project-nav"
        class:chosen={!project}
        onclick={() => {
          project = '';
          void changeProject();
        }}><span class="project-glyph">▦</span>All projects</button
      >
      {#each projects as item}<button
          class="project-nav"
          class:chosen={project === String(item.id)}
          onclick={() => {
            project = String(item.id);
            if (['Settings', 'Projects', 'Inbox'].includes(view)) view = 'All Issues';
            void changeProject();
          }}><span class="project-glyph">{item.key.slice(0, 1)}</span>{item.name}</button
        >{/each}
      <div class="sidebar-bottom">
        <button onclick={() => navigate('Settings')} class:active={view === 'Settings'}
          ><span class="nav-icon">⚙</span>Settings</button
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
                    : view === 'My Issues'
                      ? 'Your next steps, all in one place.'
                      : 'Everything your team is moving forward.'}
          </p>
        </div>
        {#if ['All Issues', 'Board'].includes(view)}<div class="view-switch">
            <button class:active={view === 'All Issues'} onclick={() => navigate('All Issues')}
              >☷ List</button
            ><button class:active={view === 'Board'} onclick={() => navigate('Board')}
              >▥ Board</button
            >
          </div>{/if}
      </div>
      {#if ['All Issues', 'My Issues', 'Approvals', 'Board'].includes(view)}
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
            ><option value="">All projects</option>{#each projects as p}<option value={p.id}
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
            />Following</label
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
                        data-priority={item.priority}>{item.priority === 'URGENT' ? '!' : '≡'}</span
                      >
                    </div>
                    <strong>{item.title}</strong>
                    <div class="card-footer">
                      {#if item.approval === 'PENDING'}<span class="approval-tag">◇ Pending</span
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
          <div class="issue-table">
            <div class="table-head">
              <span></span><span>Priority</span><span>Issue</span><span class="grow">Title</span
              ><span>Status</span><span>{view === 'Approvals' ? 'Author' : 'Assignee'}</span><span
                >{view === 'Approvals' ? 'Submitted' : 'Updated'}</span
              >
            </div>
            {#each tasks as item, i}<div
                class="issue-row"
                class:focused={focusIndex === i}
                class:selected={selected.has(item.identifier)}
                id={`row-${i}`}
              >
                <div class="select-cell">
                  <input
                    type="checkbox"
                    aria-label={`Select ${item.identifier}`}
                    checked={selected.has(item.identifier)}
                    onchange={() => toggleSelect(item.identifier)}
                  />
                </div>
                <button
                  class="row-open"
                  onclick={() => {
                    focusIndex = i;
                    void openTask(item.identifier);
                  }}
                  ><span class="priority" data-priority={item.priority} title={label(item.priority)}
                    >{item.priority === 'URGENT' ? '!' : item.priority === 'NONE' ? '–' : '≡'}</span
                  ><span class="issue-id">{item.identifier}</span><span class="issue-title"
                    ><strong>{item.title}</strong
                    >{#each labels(item.labels).slice(0, 2) as tag}<span class="tag">{tag}</span
                      >{/each}{#if item.approval === 'PENDING'}<span class="approval-tag"
                        >◇ Review needed</span
                      >{/if}</span
                  ><span class="status-cell"
                    ><span class="status-dot" style:background={colors[item.status]}></span>{label(
                      item.status,
                    )}</span
                  ><span class="assignee-cell"
                    ><span
                      class="avatar tiny"
                      title={name(view === 'Approvals' ? item.creatorId : item.assigneeId)}
                      >{view === 'Approvals'
                        ? name(item.creatorId).slice(0, 2)
                        : item.assigneeId
                          ? name(item.assigneeId).slice(0, 2)
                          : '–'}</span
                    ></span
                  ><span class="row-date"
                    >{date(view === 'Approvals' ? item.createdAt : item.updatedAt)}</span
                  ></button
                >
              </div>{/each}
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
      {:else if view === 'Inbox'}
        <div class="section-toolbar">
          <span>{unread} unread notifications</span><button
            disabled={!unread || busy}
            onclick={() =>
              run(async () => {
                await api('/notifications/read-all', 'POST');
                await loadNotifications();
              })}>Mark all as read</button
          >
        </div>
        <div class="notifications">
          {#each notifications as n}<button
              class="notification"
              class:unread={n.unread}
              onclick={() =>
                run(async () => {
                  await api(`/notifications/${n.id}/read`, 'POST');
                  await loadNotifications();
                  await openTask(n.identifier);
                })}
              ><span class="notification-dot"></span>
              <div>
                <strong>{n.message}</strong>
                <p>{n.title}</p>
              </div>
              <time>{date(n.created_at)}</time></button
            >{/each}
        </div>
        {#if !notifications.length}<div class="empty">
            <div class="empty-symbol">◫</div>
            <h2>You're all caught up</h2>
            <p>Follow issues to receive updates here.</p>
          </div>{/if}
        <div class="pagination">
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
      {:else if view === 'Projects'}
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
                  <button
                    class="text-button"
                    onclick={() => {
                      project = String(p.id);
                      view = 'All Issues';
                      void changeProject();
                    }}>{p.name}</button
                  ><span class="tag">{p.key}</span>
                </h2>
                <p>{p.description || 'No project description yet.'}</p>
                {#if p.repository}<a href={p.repository} target="_blank" rel="noreferrer"
                    >{p.repository.replace('https://github.com/', '')} ↗</a
                  >{/if}
              </div>
              {#if owner}<button onclick={() => projectEditor(p)}>Edit project</button>{/if}
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
      {:else if view === 'Settings'}
        <div class="settings-tabs">
          {#each owner ? ['Account', 'People', 'Project access', 'Agent tokens', 'Audit log'] : ['Account'] as section}<button
              class:active={settingsTab === section}
              onclick={() => (settingsTab = section)}>{section}</button
            >{/each}
        </div>
        <div class="settings-content">
          {#if settingsTab === 'Account'}<section>
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
              <h2>Invite a developer</h2>
              <p class="muted">Single-use link, valid for 7 days. Grant project access after registration.</p>
              <button disabled={busy} onclick={() => run(async () => {
                const result = await api<Entry>('/invitations', 'POST');
                freshInvitation = `${location.origin}/#invite=${result.token}`;
                await loadSettings();
              })}>Create invitation link</button>
              {#if freshInvitation}<label>Copy this link now<input readonly value={freshInvitation} onclick={(event) => event.currentTarget.select()} /></label>
                <button onclick={() => run(async () => { await navigator.clipboard.writeText(freshInvitation); notify('Invitation copied'); })}>Copy link</button>{/if}
              <div class="member-list">{#each invitations as invite}<div>
                <strong>Invitation #{invite.id}</strong>
                <span class="muted">Expires {new Date(invite.expires_at).toLocaleString()}</span>
                <span>{invite.used_by ? 'Accepted' : invite.revoked ? 'Revoked' : Date.parse(invite.expires_at) <= Date.now() ? 'Expired' : 'Pending'}</span>
                {#if !invite.used_by && !invite.revoked && Date.parse(invite.expires_at) > Date.now()}<button disabled={busy} onclick={() => run(async () => {
                  await api(`/invitations/${invite.id}`, 'DELETE');
                  await loadSettings();
                })}>Revoke</button>{/if}
              </div>{/each}</div>
            </section><section>
              <h2>People & agents</h2>
              <div class="member-list">
                {#each allUsers as user}<div>
                    <span class="avatar">{user.display_name.slice(0, 2)}</span><strong
                      >{user.display_name}</strong
                    ><span class="muted">@{user.username}</span><span class="tag"
                      >{label(user.kind)}</span
                    ><button
                      disabled={busy || user.id === me.id}
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
              <h2>Add participant</h2>
              <form class="settings-form" onsubmit={createUser}>
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
                    ><option value="AGENT">Agent</option></select
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
              </form>
            </section>
          {:else if settingsTab === 'Project access'}<section>
              <h2>Project participants</h2>
              <p class="muted">
                Owners have access to every project. Other participants need an explicit permission.
              </p>
              <form class="settings-form" onsubmit={saveMember}>
                <label
                  >Project<select bind:value={project} onchange={changeProject} required
                    ><option value="">Select project</option>{#each projects as p}<option
                        value={p.id}>{p.name}</option
                      >{/each}</select
                  ></label
                ><label
                  >Participant<select bind:value={memberUser} required
                    ><option value="">Select participant</option
                    >{#each allUsers.filter((u) => u.kind !== 'OWNER') as user}<option
                        value={user.id}>{user.display_name} · {label(user.kind)}</option
                      >{/each}</select
                  ></label
                ><label
                  >Permission<select bind:value={memberPermission}
                    ><option value="READ">Read</option><option value="WRITE"
                      >Write / claim own work</option
                    ><option value="MANAGE">Manage all project tasks</option><option value="REMOVE"
                      >Remove access</option
                    ></select
                  ></label
                ><button disabled={busy || !project}>Save access</button>
              </form>
              {#if project}<div class="member-list">
                  {#each people as p}<div>
                      <strong>{p.display_name}</strong><span>{p.permission}</span>
                    </div>{/each}
                </div>{/if}
            </section>
          {:else if settingsTab === 'Agent tokens'}<section>
              <h2>MCP access</h2>
              <p class="muted">
                Streamable HTTP endpoint: <code>{location.origin}/mcp</code>. Each token belongs to
                one agent and one project. Approval permissions are never granted.
              </p>
              {#if freshToken}<div class="token-reveal">
                  <strong>Copy this token now. It will not be shown again.</strong><textarea
                    readonly
                    value={freshToken}
                    aria-label="New agent token"></textarea><button
                    onclick={() => {
                      freshToken = '';
                    }}>I have saved the token</button
                  >
                </div>{/if}
              <form class="settings-form" onsubmit={createToken}>
                <label
                  >Agent<select bind:value={tokenForm.user} required
                    ><option value="">Select agent</option
                    >{#each allUsers.filter((u) => u.kind === 'AGENT' && u.enabled) as agent}<option
                        value={agent.id}>{agent.display_name}</option
                      >{/each}</select
                  ></label
                ><label
                  >Project<select bind:value={tokenForm.project} required
                    ><option value="">Select project</option>{#each projects as p}<option
                        value={p.id}>{p.name}</option
                      >{/each}</select
                  ></label
                ><label
                  >Token name<input bind:value={tokenForm.name} maxlength="100" required /></label
                ><label
                  >Permission<select bind:value={tokenForm.permission}
                    ><option value="READ">Read</option><option value="WRITE">Write</option><option
                      value="MANAGE">Task management</option
                    ></select
                  ></label
                ><label
                  >Expiration (optional)<input
                    type="datetime-local"
                    bind:value={tokenForm.expiresAt}
                  /></label
                ><button disabled={busy}>Create token</button>
              </form>
              <div class="member-list">
                {#each tokens as token}<div>
                    <strong>{token.name}</strong><span>@{token.username}</span><span class="tag"
                      >{token.permission}</span
                    ><small>{token.expires_at ? date(token.expires_at) : 'No expiry'}</small><button
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
      {/if}
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
            >{detail!.subscribed ? '◉ Following' : '◎ Follow'}</button
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
              >{#each transitions[task.status] ?? [] as s}<option value={s}>{label(s)}</option
                >{/each}</select
            ><span>Priority</span><strong class="priority-text" data-priority={task.priority}
              >{label(task.priority)}</strong
            ><span>Assignee</span><select
              aria-label="Issue assignee"
              disabled={busy || task.archived || !canManage}
              value={task.assigneeId ?? ''}
              onchange={(e) =>
                taskAction('assign', {
                  assignee: e.currentTarget.value ? Number(e.currentTarget.value) : null,
                })}
              ><option value="">Unassigned</option
              >{#each people.filter((p) => p.kind === 'OWNER' || ['WRITE', 'MANAGE'].includes(p.permission ?? '')) as p}<option
                  value={p.id}>{p.display_name}{p.kind === 'AGENT' ? ' · Agent' : ''}</option
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
            ><option value="">Select project</option>{#each projects as p}<option value={p.id}
                >{p.name}</option
              >{/each}</select
          ></label
        ><input
          class="title-input"
          bind:value={newTask.title}
          placeholder="Issue title"
          required
          maxlength="240"
          aria-label="Issue title"
        /><textarea
          class="description-input"
          bind:value={newTask.description}
          placeholder="Describe the problem, requirements and expected outcome…"
          maxlength="100000"
          aria-label="Description"></textarea>
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
        <p class="fine">Every proposal starts in Backlog and requires human approval.</p>
        <footer>
          <button type="button" onclick={() => (createOpen = false)}>Cancel</button><button
            class="primary"
            disabled={busy || !createProject}>Create proposal</button
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
            ><option value="">Select project</option>{#each projects as p}<option value={p.id}
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
