<script lang="ts">
  import { getWorkspaceViews, type WorkspaceViews } from './workspace-views';

  let { name }: { name: keyof WorkspaceViews } = $props();
  const views = getWorkspaceViews();
</script>

{@render views[name]()}
