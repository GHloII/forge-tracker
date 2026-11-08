<script lang="ts">
  let { value = $bindable(''), readonly = false, placeholder = '', pasteImage }: {
    value: string;
    readonly?: boolean;
    placeholder?: string;
    pasteImage: (event: ClipboardEvent) => string;
  } = $props();
  let editor: HTMLDivElement;

  function fragment(markdown: string) {
    const result = document.createDocumentFragment();
    const images = /!\[([^\]\n]*)\]\((\/api\/attachments\/[a-zA-Z0-9-]+|blob:https?:\/\/[^\s)"<>]+)\)/g;
    let offset = 0;
    for (const match of markdown.matchAll(images)) {
      result.append(markdown.slice(offset, match.index));
      const image = document.createElement('img');
      image.src = match[2];
      image.alt = match[1];
      image.dataset.markdown = match[0];
      image.draggable = false;
      result.append(image);
      offset = match.index + match[0].length;
    }
    result.append(markdown.slice(offset));
    return result;
  }

  function markdown(node: Node): string {
    if (node.nodeType === Node.TEXT_NODE) return node.textContent ?? '';
    if (node instanceof HTMLImageElement) return node.dataset.markdown ?? '';
    if (node instanceof HTMLBRElement) return '\n';
    // A lone <br> is the browser's caret placeholder for an empty line.
    if (node.childNodes.length === 1 && node.firstChild instanceof HTMLBRElement) return '';
    return Array.from(node.childNodes).map((child, index) => {
      const block = child instanceof HTMLElement && ['DIV', 'P'].includes(child.tagName);
      return (block && index > 0 ? '\n' : '') + markdown(child);
    }).join('');
  }

  $effect(() => {
    if (editor && markdown(editor) !== value) editor.replaceChildren(fragment(value));
  });

  function paste(event: ClipboardEvent) {
    event.preventDefault();
    if (readonly) return;
    const image = pasteImage(event);
    const content = image || event.clipboardData?.getData('text/plain') || '';
    const container = document.createElement('div');
    container.append(fragment(content));
    // Native insertion preserves the browser's caret placement and undo history.
    document.execCommand('insertHTML', false, container.innerHTML);
    value = markdown(editor);
  }
</script>

<div
  bind:this={editor}
  class="description-input description-editor"
  role="textbox"
  aria-label="Description"
  aria-multiline="true"
  aria-readonly={readonly}
  contenteditable={!readonly}
  tabindex="0"
  data-placeholder={placeholder}
  oninput={() => value = markdown(editor)}
  onpaste={paste}
  ondrop={(event) => event.preventDefault()}
></div>

<style>
  .description-editor {
    border: 1px solid var(--border);
    border-radius: 5px;
    padding: 8px 10px;
    outline: none;
    line-height: 1.65;
    white-space: pre-wrap;
    overflow-wrap: anywhere;
    cursor: text;
    color: var(--text);
  }
  .description-editor:focus { border-color: var(--accent); }
  .description-editor:empty::before { content: attr(data-placeholder); color: var(--subtle); pointer-events: none; }
  .description-editor :global(img) { max-width: 100%; max-height: 400px; height: auto; vertical-align: middle; }
</style>
