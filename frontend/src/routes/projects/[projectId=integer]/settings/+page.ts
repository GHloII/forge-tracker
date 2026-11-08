import type { PageLoad } from './$types';

// Project IDs are runtime data; Spring Boot serves the client fallback for these URLs.
export const prerender = false;

export const load: PageLoad = () => ({ view: 'Project settings', section: 'Account' });
