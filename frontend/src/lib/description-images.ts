import { api } from './api';

// Draft images belong to this workspace instance, never to another editor session.
export class DescriptionImages {
  private images = new Map<string, { file: File; uploaded?: string }>();

  paste(event: ClipboardEvent): string {
    const files = Array.from(event.clipboardData?.files ?? []).filter((file) => file.type.startsWith('image/'));
    if (!files.length) return '';
    event.preventDefault();
    for (const file of files) {
      if (!['image/png', 'image/jpeg', 'image/gif'].includes(file.type) || !file.size || file.size > 5 * 1024 * 1024)
        throw new Error('Paste a PNG, JPEG or GIF image of at most 5 MB.');
    }
    return files.map((file) => {
      const url = URL.createObjectURL(file);
      this.images.set(url, { file });
      return `![Pasted image](${url})`;
    }).join('\n');
  }

  hasPending(description: string): boolean {
    return [...this.images.keys()].some((url) => description.includes(url));
  }

  withoutPending(description: string): string {
    for (const url of this.images.keys()) description = description.replaceAll(`![Pasted image](${url})`, '');
    return description;
  }

  async upload(description: string, identifier: string): Promise<string> {
    for (const [url, image] of this.images) {
      if (!description.includes(url)) continue;
      if (!image.uploaded) {
        const body = new FormData();
        body.append('file', image.file);
        const result = await api<{ id: string }>(`/tasks/${identifier}/attachments`, 'POST', body);
        image.uploaded = `/api/attachments/${result.id}`;
      }
      description = description.replaceAll(url, image.uploaded);
    }
    return description;
  }

  dispose() {
    for (const url of this.images.keys()) URL.revokeObjectURL(url);
    this.images.clear();
  }
}
