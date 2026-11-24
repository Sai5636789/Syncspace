import { test, expect } from '@playwright/test';

// Smoke test: the landing page loads and the backend is reachable.
// The full multi-user scenario (register A + B, invite, concurrent edit,
// message, task move, disconnect/reconnect, restart persistence) lives in
// multi-user.spec.ts and lands with the messaging/documents slices.
test('landing page loads and backend health is ok', async ({ page, request }) => {
  await page.goto('/');
  await expect(page.locator('h1')).toContainText('SyncSpace');

  const health = await request.get('/api/v1/health');
  expect(health.ok()).toBeTruthy();
  const body = await health.json();
  expect(body.status).toBe('ok');
});
