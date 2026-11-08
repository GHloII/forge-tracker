<script lang="ts">
  let { label = 'Loading…' }: { label?: string } = $props();
</script>

<div class="loading-indicator" role="status">
  <span class="spinner" aria-hidden="true"></span>
  <span>{label}</span>
</div>

<style>
  .loading-indicator {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 10px;
    padding: 32px 16px;
    color: var(--muted);
  }

  .spinner {
    width: 16px;
    height: 16px;
    flex: none;
    border: 2px solid var(--border);
    border-top-color: var(--accent);
    border-radius: 50%;
    animation: spin 0.8s linear infinite;
  }

  @keyframes spin {
    to { transform: rotate(360deg); }
  }

  @media (prefers-reduced-motion: reduce) {
    .spinner { animation: none; }
  }
</style>
