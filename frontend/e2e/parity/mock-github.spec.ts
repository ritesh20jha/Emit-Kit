import { expect, test } from '@playwright/test';

// The WireMock GitHub stand-in that the parity stack runs (deploy/compose/docker-compose.e2e.yml).
const MOCK = 'http://127.0.0.1:8089';

test('GET /user returns the fixture user', async ({ request }) => {
  const res = await request.get(`${MOCK}/user`);
  expect(res.status()).toBe(200);
  expect(await res.json()).toMatchObject({ login: 'octocat', id: 1001 });
});

test('the OAuth authorize step redirects back with the code and the state', async ({ request }) => {
  const callback = 'http://localhost:8080/login/oauth2/code/github';
  const res = await request.get(
    `${MOCK}/login/oauth/authorize?client_id=x&redirect_uri=${encodeURIComponent(callback)}&state=abc123`,
    { maxRedirects: 0 },
  );
  expect(res.status()).toBe(302);
  expect(res.headers()['location']).toBe(`${callback}?code=mock-code&state=abc123`);
});

test('the OAuth token step returns a bearer token', async ({ request }) => {
  const res = await request.post(`${MOCK}/login/oauth/access_token`, {
    form: { client_id: 'x', client_secret: 'y', code: 'mock-code' },
  });
  expect(res.status()).toBe(200);
  expect(await res.json()).toMatchObject({ access_token: expect.any(String), token_type: 'bearer' });
});

test('the hook create and delete calls answer as GitHub does', async ({ request }) => {
  const created = await request.post(`${MOCK}/repos/acme/billing-api/hooks`, {
    data: { name: 'web', config: { url: 'http://localhost:8080/webhooks/github' }, events: ['push'] },
  });
  expect(created.status()).toBe(201);
  expect(await created.json()).toMatchObject({ id: 42, name: 'web', active: true });

  const deleted = await request.delete(`${MOCK}/repos/acme/billing-api/hooks/42`);
  expect(deleted.status()).toBe(204);
});

test('the admin API lists the OAuth and hook mappings', async ({ request }) => {
  const res = await request.get(`${MOCK}/__admin/mappings`);
  expect(res.status()).toBe(200);
  const paths: string[] = (await res.json()).mappings.map(
    (m: { request: { urlPath?: string; urlPathPattern?: string; url?: string } }) =>
      m.request.urlPath ?? m.request.urlPathPattern ?? m.request.url,
  );
  expect(paths).toEqual(
    expect.arrayContaining([
      '/login/oauth/authorize',
      '/login/oauth/access_token',
      '/repos/acme/billing-api/hooks',
    ]),
  );
});
