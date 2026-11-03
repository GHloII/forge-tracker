<script lang="ts">
  import { marked, Renderer } from 'marked';
  import DOMPurify from 'dompurify';
  let { content, openTask }: { content: string; openTask: (id: string) => void } = $props();
  const renderer = new Renderer();
  renderer.text = (token) => {
    if ('tokens' in token && token.tokens)
      return marked.Parser.parseInline(token.tokens, { renderer });
    const text = token.text
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;');
    return text.replace(/\b([A-Z][A-Z0-9]{1,9}-\d+)\b/g, '<a href="#task=$1">$1</a>');
  };
  let html = $derived(
    DOMPurify.sanitize(marked.parse(content, { async: false, renderer }) as string, {
      FORBID_TAGS: ['img', 'style', 'form', 'input'],
    }),
  );
</script>

<!-- svelte-ignore a11y_no_static_element_interactions, a11y_click_events_have_key_events -->
<div
  class="markdown"
  onclick={(event) => {
    const anchor = (event.target as HTMLElement).closest('a');
    if (anchor?.getAttribute('href')?.startsWith('#task=')) {
      event.preventDefault();
      openTask(anchor.getAttribute('href')!.slice(6));
    }
  }}
>
  {@html html}
</div>
