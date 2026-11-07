import type { PageLoad } from './$types';

export const prerender = false;

export const load: PageLoad = () => ({ view: 'My Issues', section: 'Account' });
