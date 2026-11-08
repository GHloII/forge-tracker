<script lang="ts">
  import { onMount } from 'svelte';
  import LoadingIndicator from './LoadingIndicator.svelte';
  import { api, type Entry, type Person, type Project } from './api';

  let { project, onUpdated }: { project: Project; onUpdated: () => Promise<void> } = $props();
  let tab = $state('General'),
    loading = $state(true),
    busy = $state(false),
    error = $state(''),
    message = $state('');
  let participants = $state<Person[]>([]),
    users = $state<Person[]>([]);
  let roles = $state<Entry[]>([]),
    invitations = $state<Entry[]>([]);
  let details = $state({ key: '', name: '', description: '', repository: '' });
  let inviteRole = $state(''),
    freshLink = $state('');
  let memberUser = $state(''),
    memberRole = $state('');
  let editingRole = $state<number | null>(null),
    roleName = $state(''),
    rolePermission = $state('WRITE');
  const levels = [
    { value: 'READ', name: 'Read', description: 'View project issues and activity.' },
    {
      value: 'WRITE',
      name: 'Write',
      description: 'Propose issues, claim work and edit own tasks.',
    },
    { value: 'MANAGE', name: 'Manage tasks', description: 'Manage and assign all project tasks.' },
  ];
  const endpoint = $derived(`/projects/${project.id}`);
  const availableUsers = $derived(
    users.filter(
      (u) => u.kind !== 'OWNER' && u.enabled && !participants.some((p) => p.id === u.id),
    ),
  );

  async function load() {
    loading = true;
    try {
      const [context, nextRoles, nextInvitations, nextUsers] = await Promise.all([
        api<Entry>(endpoint),
        api<Entry[]>(`${endpoint}/roles`),
        api<Entry[]>(`${endpoint}/invitations`),
        api<Person[]>('/users'),
      ]);
      participants = context.participants;
      roles = nextRoles;
      invitations = nextInvitations;
      users = nextUsers;
      if (!roles.some((r) => String(r.id) === inviteRole))
        inviteRole = String(roles.find((r) => r.permission === 'WRITE')?.id ?? roles[0]?.id ?? '');
      if (!roles.some((r) => String(r.id) === memberRole)) memberRole = inviteRole;
      if (!availableUsers.some((u) => String(u.id) === memberUser)) memberUser = '';
    } finally {
      loading = false;
    }
  }

  async function run(action: () => Promise<void>) {
    if (busy) return;
    busy = true;
    error = '';
    message = '';
    try {
      await action();
    } catch (e) {
      error = (e as Error).message;
    } finally {
      busy = false;
    }
  }

  async function saveDetails(event: SubmitEvent) {
    event.preventDefault();
    await run(async () => {
      await api(endpoint, 'PUT', details);
      await onUpdated();
      message = 'Project updated';
    });
  }

  async function saveRole(event: SubmitEvent) {
    event.preventDefault();
    await run(async () => {
      await api(
        `${endpoint}/roles${editingRole ? `/${editingRole}` : ''}`,
        editingRole ? 'PUT' : 'POST',
        { name: roleName, permission: rolePermission },
      );
      editingRole = null;
      roleName = '';
      rolePermission = 'WRITE';
      await load();
      await onUpdated();
      message = 'Role saved. Its permissions apply to current members and pending invitations.';
    });
  }

  async function assign(user: number, role: number) {
    await run(async () => {
      await api(`${endpoint}/members/${user}/role`, 'PUT', { role });
      memberUser = '';
      await load();
      await onUpdated();
      message = 'Project access updated';
    });
  }

  onMount(() => {
    details = {
      key: project.key,
      name: project.name,
      description: project.description,
      repository: project.repository,
    };
    void run(load);
  });
</script>

<div class="settings-tabs">
  {#each ['General', 'Members', 'Invitations', 'Roles'] as section}
    <button
      class:active={tab === section}
      onclick={() => {
        tab = section;
        error = '';
        message = '';
      }}>{section}</button
    >
  {/each}
  <button class="settings-refresh" disabled={busy || loading} onclick={() => run(load)}
    >Refresh</button
  >
</div>
<div class="settings-content project-settings">
  {#if error}<div class="error" role="alert">{error}</div>{/if}
  {#if message}<p class="settings-notice" role="status">{message}</p>{/if}
  {#if loading}<LoadingIndicator label="Loading project settings…" />
  {:else if tab === 'General'}
    <section>
      <h2>Project details</h2>
      <p>Manage the name and context for {project.name}.</p>
      <form class="settings-form" onsubmit={saveDetails}>
        <label>Project key<input value={details.key} readonly /></label>
        <label>Name<input bind:value={details.name} maxlength="100" required /></label>
        <label
          >Description<textarea bind:value={details.description} maxlength="20000"
          ></textarea></label
        >
        <label
          >GitHub repository<input
            bind:value={details.repository}
            placeholder="https://github.com/team/repository"
          /></label
        >
        <button class="primary" disabled={busy}>Save project</button>
      </form>
    </section>
  {:else if tab === 'Members'}
    <section>
      <div class="section-toolbar">
        <h2>Project members</h2>
        <button
          class="primary"
          onclick={() => {
            tab = 'Invitations';
          }}>Invite to project</button
        >
      </div>
      <p>Roles control access to this project. Owners always have full access.</p>
      <div class="project-member-list">
        {#each participants as person}
          <div class="project-member-row">
            <span class="avatar">{person.display_name.slice(0, 2)}</span>
            <div class="grow">
              <strong>{person.display_name}</strong><small class="muted"
                >@{person.username}{person.kind === 'AGENT' ? ' · Agent' : ''}</small
              >
            </div>
            {#if person.kind === 'OWNER'}<span class="tag">Owner</span>
            {:else}
              <select
                aria-label={`Role for ${person.display_name}`}
                value={person.role_id ?? ''}
                disabled={busy}
                onchange={(event) => {
                  void assign(person.id, Number(event.currentTarget.value));
                }}
              >
                {#if !person.role_id}<option value="" disabled>{person.permission}</option>{/if}
                {#each roles as role}<option value={role.id}>{role.name}</option>{/each}
              </select>
              <button
                disabled={busy}
                aria-label={`Remove ${person.display_name} from project`}
                onclick={() =>
                  run(async () => {
                    await api(`${endpoint}/members`, 'POST', {
                      user: person.id,
                      permission: 'REMOVE',
                    });
                    await load();
                    await onUpdated();
                    message = 'Project access removed';
                  })}>Remove</button
              >
            {/if}
          </div>
        {/each}
      </div>
    </section>
    <section>
      <h2>Add an existing participant</h2>
      <form
        class="settings-form"
        onsubmit={(event) => {
          event.preventDefault();
          void assign(Number(memberUser), Number(memberRole));
        }}
      >
        <label
          >Participant<select bind:value={memberUser} required
            ><option value="">Select participant</option>
            {#each availableUsers as person}<option value={person.id}
                >{person.display_name} · {person.kind === 'AGENT' ? 'Agent' : 'Developer'}</option
              >{/each}
          </select></label
        >
        <label
          >Project role<select bind:value={memberRole} required
            >{#each roles as role}<option value={role.id}>{role.name}</option>{/each}</select
          ></label
        >
        <button disabled={busy || !memberUser}>Add to project</button>
      </form>
    </section>
  {:else if tab === 'Invitations'}
    <section>
      <h2>Invite to {project.name}</h2>
      <p>
        The recipient can create an account or sign in. Accepting gives them access to this project
        with the selected role. Existing project members keep their current role.
      </p>
      <form
        class="settings-form"
        onsubmit={(event) => {
          event.preventDefault();
          void run(async () => {
            const result = await api<Entry>(`${endpoint}/invitations`, 'POST', {
              role: Number(inviteRole),
            });
            freshLink = `${location.origin}/#invite=${result.token}`;
            await load();
          });
        }}
      >
        <label
          >Project role<select bind:value={inviteRole} required
            >{#each roles as role}<option value={role.id}>{role.name}</option>{/each}</select
          ></label
        >
        <p class="muted">Each link works once and expires after 7 days.</p>
        <button class="primary" disabled={busy || !inviteRole}>Create invitation link</button>
      </form>
      {#if freshLink}<div class="token-reveal">
          <strong>Copy this link now. It will not be shown again.</strong>
          <input
            aria-label="Invitation link"
            readonly
            value={freshLink}
            onclick={(event) => event.currentTarget.select()}
          />
          <div>
            <button
              onclick={() =>
                run(async () => {
                  await navigator.clipboard.writeText(freshLink);
                  message = 'Invitation copied';
                })}>Copy link</button
            >
            <button
              onclick={() => {
                freshLink = '';
              }}>Dismiss</button
            >
          </div>
        </div>{/if}
    </section>
    <section>
      <h2>Recent invitations</h2>
      {#if !invitations.length}<p class="muted">No invitations for this project yet.</p>{/if}
      <div class="project-member-list">
        {#each invitations as invite}<div class="project-member-row">
            <div class="grow">
              <strong>{invite.role_name}</strong><small class="muted"
                >Expires {new Date(invite.expires_at).toLocaleString()}</small
              >
            </div>
            <span class="tag"
              >{invite.used_by
                ? 'Accepted'
                : invite.revoked
                  ? 'Revoked'
                  : Date.parse(invite.expires_at) <= Date.now()
                    ? 'Expired'
                    : 'Pending'}</span
            >
            {#if !invite.used_by && !invite.revoked && Date.parse(invite.expires_at) > Date.now()}<button
                disabled={busy}
                onclick={() =>
                  run(async () => {
                    await api(`${endpoint}/invitations/${invite.id}`, 'DELETE');
                    freshLink = '';
                    await load();
                    message = 'Invitation revoked';
                  })}>Revoke</button
              >{/if}
          </div>{/each}
      </div>
    </section>
  {:else if tab === 'Roles'}
    <section>
      <h2>Project roles</h2>
      <p>
        Give roles names that fit your team. Each role uses one permission level. Task management
        does not grant approval, invitation or administration rights.
      </p>
      <div class="project-member-list">
        {#each roles as role}<div class="project-member-row">
            <div class="grow">
              <strong>{role.name}</strong><small class="muted"
                >{levels.find((level) => level.value === role.permission)?.description}</small
              >
            </div>
            <span class="tag">{levels.find((level) => level.value === role.permission)?.name}</span>
            {#if role.builtin}<small class="muted">Default</small>{:else}<button
                disabled={busy}
                onclick={() => {
                  editingRole = role.id;
                  roleName = role.name;
                  rolePermission = role.permission;
                }}>Edit</button
              >{/if}
          </div>{/each}
      </div>
    </section>
    <section>
      <h2>{editingRole ? 'Edit role' : 'Create role'}</h2>
      <form class="settings-form" onsubmit={saveRole}>
        <label
          >Role name<input
            bind:value={roleName}
            maxlength="60"
            placeholder="For example: Designer"
            required
          /></label
        >
        <label
          >Permission level<select bind:value={rolePermission}
            >{#each levels as level}<option value={level.value}
                >{level.name} — {level.description}</option
              >{/each}</select
          ></label
        >
        {#if editingRole}<p>
            Changing permissions updates every member with this role and all pending invitations
            that use it.
          </p>{/if}
        <button class="primary" disabled={busy}>{editingRole ? 'Save role' : 'Create role'}</button>
        {#if editingRole}<button
            type="button"
            onclick={() => {
              editingRole = null;
              roleName = '';
              rolePermission = 'WRITE';
            }}>Cancel</button
          >{/if}
      </form>
    </section>
  {/if}
</div>
