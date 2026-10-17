// The web app on a real deployment, in a real browser (Phase 4a): the gateway's
// Content-Security-Policy must not block anything the app does. Run against a public URL:
//   cd web && DEPLOYED_URL=https://<host> DEPLOYED_INVITE=<code> npx playwright test e2e/deployed.spec.ts
// Signs up through the UI with the invite code, creates a board, adds a list and a card, reloads
// (the service worker), then deletes the account again.
import { expect, test } from "@playwright/test";

const URL_ = process.env.DEPLOYED_URL?.replace(/\/$/, "");
const INVITE = process.env.DEPLOYED_INVITE ?? "";

test("the app works under the production CSP, with no violations", async ({ browser }) => {
  test.skip(!URL_, "set DEPLOYED_URL");
  const ctx = await browser.newContext({ baseURL: URL_! });
  const page = await ctx.newPage();
  const violations: string[] = [];
  await page.addInitScript(() => {
    document.addEventListener("securitypolicyviolation", (e) => console.error(`CSP violation: ${e.violatedDirective} ${e.blockedURI}`));
  });
  page.on("console", (m) => /Content Security Policy|CSP violation/i.test(m.text()) && violations.push(m.text()));
  const response = await page.goto("/");
  expect(response!.headers()["content-security-policy"]).toContain("default-src 'self'");

  const email = `deployed-${crypto.randomUUID().slice(0, 8)}@smoke.invalid`;
  await page.getByRole("button", { name: "New here? Create an account" }).click();
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Your name (shown to others)").fill("Deployed Check");
  await page.getByLabel("Password").fill("deployed check password");
  await page.getByLabel("Invite code (if you were given one)").fill(INVITE);
  await page.getByRole("button", { name: "Create account" }).click();
  await page.getByLabel("New board title").fill("CSP check");
  await page.getByRole("button", { name: "Create board" }).click();
  await page.waitForFunction(() => window.concord?.client.status === "joined", undefined, { timeout: 60_000 });
  await page.getByLabel("New list title").fill("Works");
  await page.getByLabel("New list title").press("Enter");
  await page.getByLabel("New card title").fill("under the CSP");
  await page.getByLabel("New card title").press("Enter");
  await expect(page.getByTestId("card").filter({ hasText: "under the CSP" })).toBeVisible();
  await page.waitForFunction(() => window.concord!.client.pendingOps().length === 0, undefined, { timeout: 30_000 });
  await page.waitForFunction(async () => (await navigator.serviceWorker.ready) && navigator.serviceWorker.controller !== null);
  await page.reload();
  await expect(page.getByTestId("card").filter({ hasText: "under the CSP" })).toBeVisible();
  expect(violations).toEqual([]);

  const del = await ctx.request.delete("/api/me", { data: { password: "deployed check password" }, headers: { Origin: URL_! } });
  expect(del.status()).toBe(200);
  await ctx.close();
});
