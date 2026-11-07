<script lang="ts">
  let { name }: { name: string } = $props();
  const paths: Record<string, string> = {
    check: 'M5 12l4 4L19 6', close: 'M6 6l12 12M18 6 6 18',
    plus: 'M12 5v14M5 12h14', refresh: 'M19 12a7 7 0 1 1-2-5M19 4v4h-4',
    bell: 'M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4',
    bellOn: 'M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4M3 5l2-2M19 3l2 2',
    settings: 'M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M19.1 4.9 17 7M7 17l-2.1 2.1M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0',
    logout: 'M10 4H4v16h6M14 8l4 4-4 4M8 12h10',
    diamond: 'M12 3 21 12 12 21 3 12z', dot: 'M12 7a5 5 0 1 0 0 10 5 5 0 0 0 0-10',
    left: 'M19 12H5m7-7-7 7 7 7', right: 'M5 12h14m-7-7 7 7-7 7',
    external: 'M13 5h6v6M19 5l-9 9M19 14v5H5V5h5',
    grid: 'M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z',
    list: 'M9 6h11M9 12h11M9 18h11M4 6h.01M4 12h.01M4 18h.01',
    inbox: 'M4 5h16v14H4zM4 14h5l2 2h2l2-2h5',
    moon: 'M20 14A8 8 0 0 1 10 4a8 8 0 1 0 10 10z',
    sun: 'M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6 7 7M17 17l1.4 1.4M18.4 5.6 17 7M7 17l-1.4 1.4M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0',
  };
</script>

<svg class="icon" aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d={paths[name]} /></svg>

<style>
  .icon { display: inline-block; width: 1em; height: 1em; flex: none; vertical-align: -0.125em; }
</style>
