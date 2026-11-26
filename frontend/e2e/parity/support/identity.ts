import type { Browser, BrowserContext, Page } from '@playwright/test';
import { login } from './login';
import { addMapping } from './mockStubs';
import { newSessionContext } from './session';

/**
 * The second GitHub user the roles tests use. The mock has one fixture user (octocat, the only admin of acme), and a
 * sole admin cannot demote itself, so a viewer needs a second person. This user exists only while the identity stubs
 * below are in place: the mock answers GET /user with it, so an OAuth sign-in as it creates a second EmitKit user.
 */
export const VIEWER = { login: 'viewer-e2e', id: 1002 } as const;

/**
 * Makes the mock answer the sign-in as VIEWER: GET /user returns it, and its membership of acme is "member", which
 * GitHub sync maps to EDITOR. Both stubs are temporary (e2eTemp); the caller's afterEach sweeps them.
 */
export async function addViewerIdentity(): Promise<void> {
  await addMapping({
    priority: 1,
    request: { method: 'GET', urlPath: '/user' },
    response: {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        login: VIEWER.login,
        id: VIEWER.id,
        type: 'User',
        name: 'Viewer E2E',
        email: null,
        avatar_url: `https://avatars.githubusercontent.com/u/${VIEWER.id}?v=4`,
      }),
    },
  });
  await addMapping({
    priority: 1,
    request: { method: 'GET', urlPath: '/user/memberships/orgs' },
    response: {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify([
        {
          state: 'active',
          role: 'member',
          organization: { login: 'acme', id: 2001, type: 'Organization', name: 'Acme', avatar_url: 'https://avatars.githubusercontent.com/u/2001?v=4' },
          user: { login: VIEWER.login, id: VIEWER.id, type: 'User' },
        },
      ]),
    },
  });
}

/** Signs in as the viewer in a context of its own. The caller closes the context. */
export async function signInAsViewer(browser: Browser): Promise<{ context: BrowserContext; page: Page }> {
  const context = await newSessionContext(browser);
  const page = await context.newPage();
  try {
    await login(page);
  } catch (error) {
    await context.close();
    throw error;
  }
  return { context, page };
}
