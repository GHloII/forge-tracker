import type { PageLoad } from './$types';

export const prerender = false;

export const load: PageLoad = () => ({ view: 'All Issues', section: 'Account' });
