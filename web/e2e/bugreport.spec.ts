// The trial's bug report: the form shows exactly what will be attached, the tester can remove
// items, and what is stored is that, with secrets redacted.
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { expect, test } from "@playwright/test";
import { createBoard, openBoard, signUp } from "./helpers";

const COMPOSE = fileURLToPath(new URL("../../deploy/docker-compose.yml", import.meta.url));
const psql = (sql: string) =>
  execFileSync("docker", ["compose", "-f", COMPOSE, "exec", "-T", "postgres", "psql", "-U", "concord", "-d", "concord", "-At", "-c", sql], { encoding: "utf8" }).trim();

test("a bug report carries exactly what the tester left ticked", async ({ browser }) => {
  const a = await browser.newContext();
  const me = await signUp(a, "Bug Finder");
  const board = await createBoard(a, "Bugs");
  const p = await a.newPage();
  await openBoard(p, board);
  await p.getByRole("button", { name: "Report a bug" }).click();
  const form = p.getByTestId("bug-report");
  // Every item is shown in full before sending.
  const items = form.getByTestId("diag-item");
  await expect(items.locator("label")).toHaveText([/app/, /connection/, /unsent edits/, /recent events/]);
  await items.nth(1).getByText("Show what is sent").click();
  await expect(items.nth(1).locator("pre")).toContainText('"status": "joined"');
  await expect(items.nth(1).getByRole("checkbox")).toBeChecked(); // looking does not untick
  await form.getByLabel("What happened").fill("The card jumped when I dragged it");
  await items.nth(3).getByRole("checkbox").uncheck(); // no recent events, please
  await form.getByRole("button", { name: "Send report" }).click();
  await expect(form).toContainText("Thank you. Report");

  const row = JSON.parse(psql(`SELECT json_build_object('d', description, 'keys', (SELECT json_agg(k ORDER BY k) FROM jsonb_object_keys(diagnostics) k)) FROM bug_reports WHERE user_id = '${me.id}'`)) as { d: string; keys: string[] };
  expect(row).toEqual({ d: "The card jumped when I dragged it", keys: ["app", "connection", "unsent_edits"] });
  await a.close();
});
