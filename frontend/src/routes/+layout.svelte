<script lang="ts">
  import '../style.css';
  import { page } from '$app/state';
  import Workspace from '$lib/Workspace.svelte';
  import type { Snippet } from 'svelte';

  let { children }: { children: Snippet } = $props();
</script>

<Workspace view={page.data.view ?? 'All Issues'} section={page.data.section ?? 'Account'}>
  {@render children()}
</Workspace>
