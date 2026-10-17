// Shared steps for the Playwright tests. Users and boards are made through the REST API, the
// same calls the UI makes; edits go through the UI or through window.concord.client, the
// client-core instance the UI itself drives (no devtools, no private hooks).
import { expect, type BrowserContext, type Page } from "@playwright/test";

export const BASE = "http://localhost:4173";
const origin = { Origin: BASE };

export interface User {
  id: string;
  email: string;
  name: string;
}

export async function signUp(ctx: BrowserContext, name: string): Promise<User> {
  const email = `${name.toLowerCase().replace(/\W+/g, "")}-${crypto.randomUUID().slice(0, 8)}@trial.example`;
  const r = await ctx.request.post("/api/auth/sign-up", { data: { email, name, password: "correct horse battery" }, headers: origin });
  expect(r.status(), await r.text()).toBe(201);
  return (await r.json()) as User;
}

export async function createBoard(ctx: BrowserContext, title: string): Promise<string> {
  const r = await ctx.request.post("/api/boards", { data: { title }, headers: origin });
  expect(r.status()).toBe(201);
  return ((await r.json()) as { id: string }).id;
}

export async function addMember(ctx: BrowserContext, board: string, email: string, role = "editor") {
  const r = await ctx.request.post(`/api/boards/${board}/members`, { data: { email, role }, headers: origin });
  expect(r.status(), await r.text()).toBe(201);
}

export async function removeMember(ctx: BrowserContext, board: string, user: string) {
  const r = await ctx.request.delete(`/api/boards/${board}/members/${user}`, { headers: origin });
  expect(r.status(), await r.text()).toBe(200);
}

/** Opens the board and waits until it has joined; also waits for the offline app shell. */
export async function openBoard(page: Page, board: string) {
  await page.goto(`/#/b/${board}`);
  await joined(page);
  await page.waitForFunction(async () => {
    await navigator.serviceWorker.ready;
    return navigator.serviceWorker.controller !== null;
  });
}

export async function joined(page: Page, timeout = 60_000) {
  await page.waitForFunction(() => window.concord?.client.status === "joined", undefined, { timeout });
}

/** Joined, nothing pending. */
export async function settled(page: Page, timeout = 120_000) {
  await page.waitForFunction(() => window.concord?.client.status === "joined" && window.concord.client.pendingOps().length === 0, undefined, { timeout });
}

export const pending = (page: Page) => page.evaluate(() => window.concord!.client.pendingOps().length);
export const unsent = (page: Page) => page.evaluate(() => window.concord!.client.unsentEdits().length);
export const hash = (page: Page) => page.evaluate(() => window.concord!.client.confirmedHash());
export const version = (page: Page) => page.evaluate(() => window.concord!.client.confirmedVersion);

/** Waits until both pages are joined, settled and at the same confirmed version. */
export async function converged(a: Page, b: Page) {
  await settled(a);
  await settled(b);
  await expect.poll(async () => (await version(a)) === (await version(b)), { timeout: 60_000 }).toBe(true);
  await expect.poll(async () => (await hash(a)) === (await hash(b)), { timeout: 60_000 }).toBe(true);
}
