// The board UI end to end: create, edit, labels, assignee, delete and restore, Orphaned,
// keyboard drag and drop, history, presence.
import { expect, test } from "@playwright/test";
import { addMember, createBoard, openBoard, settled, signUp } from "./helpers";

test("create lists and cards, edit fields, labels and assignee, see it all on a second browser", async ({ browser }) => {
  const a = await browser.newContext(), b = await browser.newContext();
  const alice = await signUp(a, "Alice Example");
  const bob = await signUp(b, "Bob Example");
  const board = await createBoard(a, "Club board");
  await addMember(a, board, bob.email);
  const pa = await a.newPage(), pb = await b.newPage();
  await openBoard(pa, board);
  await openBoard(pb, board);

  await pa.getByLabel("New list title").fill("To do");
  await pa.getByLabel("New list title").press("Enter");
  await pa.getByLabel("New card title").fill("Book the hall");
  await pa.getByLabel("New card title").press("Enter");
  await expect(pb.getByTestId("card").filter({ hasText: "Book the hall" })).toBeVisible();

  await pa.getByTestId("card").filter({ hasText: "Book the hall" }).click();
  const dialog = pa.getByTestId("card-dialog");
  await dialog.getByLabel("Description").fill("Ask for the big room");
  await dialog.getByLabel("Labels").fill(" urgent, budget, urgent ,été");
  await dialog.getByLabel("Labels").press("Enter");
  await expect(dialog.getByLabel("Labels")).toHaveValue("budget, urgent, été"); // sorted, unique, NFC
  await dialog.getByLabel("Assignee").selectOption({ label: "Bob Example" });
  await dialog.getByLabel("Title").fill("Book the hall for June");
  await dialog.getByLabel("Close").click();

  const card = pb.getByTestId("card").filter({ hasText: "Book the hall for June" });
  await expect(card).toBeVisible();
  await expect(card.locator(".label")).toHaveText(["budget", "urgent", "été"]);
  await card.click();
  await expect(pb.getByTestId("card-dialog").getByLabel("Description")).toHaveValue("Ask for the big room");
  await expect(pb.getByTestId("card-dialog").getByLabel("Assignee")).toHaveValue(bob.id);
  void alice;
  await a.close();
  await b.close();
});

test("delete and restore from the trash; cards of a deleted list wait in Orphaned (§3.6)", async ({ browser }) => {
  const a = await browser.newContext();
  await signUp(a, "Trash Tester");
  const board = await createBoard(a, "Trash board");
  const p = await a.newPage();
  await openBoard(p, board);
  await p.evaluate(() => {
    const c = window.concord!.client;
    const l = c.addList("Ideas");
    c.addCard(l, "Keep me");
    c.addCard(l, "Delete me");
  });
  await p.getByTestId("card").filter({ hasText: "Delete me" }).click();
  await p.getByRole("button", { name: "Delete card" }).click();
  await expect(p.getByTestId("card").filter({ hasText: "Delete me" })).toHaveCount(0);

  await p.getByRole("button", { name: "Delete list Ideas" }).click();
  const orphaned = p.getByTestId("list").filter({ hasText: "Orphaned" });
  await expect(orphaned.getByTestId("card")).toHaveText([/Keep me/]);

  await p.getByRole("button", { name: "Trash" }).click();
  const trash = p.getByTestId("trash");
  await expect(trash.getByTestId("trash-item")).toHaveCount(2);
  await trash.getByTestId("trash-item").filter({ hasText: "List: Ideas" }).getByRole("button", { name: "Restore" }).click();
  await expect(orphaned).toHaveCount(0);
  await trash.getByTestId("trash-item").filter({ hasText: "Card: Delete me" }).getByRole("button", { name: "Restore" }).click();
  await expect(p.getByTestId("list").filter({ hasText: "Ideas" }).getByTestId("card")).toHaveText([/Keep me|Delete me/, /Keep me|Delete me/]);
  await settled(p);
  await a.close();
});

test("cards move with the keyboard alone: Space, arrows, Space", async ({ browser }) => {
  const a = await browser.newContext();
  await signUp(a, "Keyboard User");
  const board = await createBoard(a, "Keys");
  const p = await a.newPage();
  await openBoard(p, board);
  await p.evaluate(() => {
    const c = window.concord!.client;
    const l = c.addList("L");
    for (const t of ["one", "two", "three"]) c.addCard(l, t);
  });
  const titles = () => p.getByTestId("card").locator(".card-title").allTextContents();
  await expect.poll(titles).toEqual(["one", "two", "three"]);
  await p.getByTestId("card").filter({ hasText: "one" }).focus();
  // At a person's pace: dnd-kit measures the layout between keyboard steps.
  for (const key of ["Space", "ArrowDown", "ArrowDown", "Space"]) {
    await p.keyboard.press(key);
    await p.waitForTimeout(250);
  }
  await expect.poll(titles).toEqual(["two", "three", "one"]);

  // Moving to another list without a mouse: the card's Move controls.
  await p.evaluate(() => window.concord!.client.addList("Done"));
  await p.getByTestId("card").filter({ hasText: "two" }).focus();
  await p.keyboard.press("Enter");
  const d = p.getByTestId("card-dialog");
  await d.getByLabel("Move to list").selectOption({ label: "Done" });
  await d.getByRole("button", { name: "Move", exact: true }).click();
  await expect(p.getByTestId("list").filter({ hasText: "Done" }).getByTestId("card")).toHaveText([/two/]);
  await settled(p);
  await a.close();
});

test("history shows who changed what, newest first", async ({ browser }) => {
  const a = await browser.newContext();
  await signUp(a, "Hist Orian");
  const board = await createBoard(a, "History board");
  const p = await a.newPage();
  await openBoard(p, board);
  await p.evaluate(() => {
    const c = window.concord!.client;
    const l = c.addList("Plans");
    const card = c.addCard(l, "Picnic");
    c.setField(card, "title", "Picnic in May");
  });
  await settled(p);
  await p.getByRole("button", { name: "History" }).click();
  const items = p.getByTestId("history-item");
  await expect(items).toHaveCount(3);
  await expect(items.nth(0)).toContainText("Hist Orian set the title of");
  await expect(items.nth(0)).toContainText("Picnic in May");
  await expect(items.nth(2)).toContainText("added list “Plans”");
  await a.close();
});

test("presence: who is on the board and which card they have open (§1.1)", async ({ browser }) => {
  const a = await browser.newContext(), b = await browser.newContext();
  await signUp(a, "Ann Owner");
  const viewer = await signUp(b, "Vic Viewer");
  const board = await createBoard(a, "Presence");
  await addMember(a, board, viewer.email, "viewer");
  const pa = await a.newPage(), pb = await b.newPage();
  await openBoard(pa, board);
  await pa.evaluate(() => {
    const c = window.concord!.client;
    c.addCard(c.addList("L"), "Shared card");
  });
  await openBoard(pb, board);
  await expect(pa.locator(".who .avatar")).toHaveText(["VV"]);
  await pb.getByTestId("card").filter({ hasText: "Shared card" }).click();
  await expect(pa.getByTestId("card").filter({ hasText: "Shared card" }).locator(".viewers")).toHaveText("👁 Vic Viewer");
  await expect(pb.getByText("You can view this board but not edit it.")).toBeVisible();
  await b.close();
  await expect(pa.locator(".who .avatar")).toHaveCount(0);
  await a.close();
});

test("cards and lists move with the mouse: reorder, across lists, never into Orphaned", async ({ browser }) => {
  const a = await browser.newContext();
  await signUp(a, "Mouse User");
  const board = await createBoard(a, "Mouse");
  const p = await a.newPage();
  if (process.env.DEBUG_DND) p.on("console", (m) => m.text().startsWith("DRAGEND") && console.log(m.text()));
  await openBoard(p, board);
  await p.evaluate(() => {
    const c = window.concord!.client;
    const l = c.addList("Left");
    const r = c.addList("Right");
    for (const t of ["a1", "a2", "a3"]) c.addCard(l, t);
    c.addCard(r, "b1");
  });
  const titles = (list: string) => p.getByTestId("list").filter({ hasText: list }).locator(".card-title").allTextContents();
  await expect.poll(() => titles("Left")).toEqual(["a1", "a2", "a3"]);
  const drag = async (from: string, to: string, below = false) => {
    const f = await p.getByTestId("card").filter({ hasText: from }).boundingBox();
    const t = await p.getByTestId("card").filter({ hasText: to }).boundingBox();
    await p.mouse.move(f!.x + f!.width / 2, f!.y + f!.height / 2);
    await p.mouse.down();
    await p.mouse.move(f!.x + f!.width / 2 + 10, f!.y + f!.height / 2 + 10, { steps: 5 });
    await p.mouse.move(t!.x + t!.width / 2, t!.y + (below ? t!.height * 0.9 : t!.height * 0.3), { steps: 15 });
    await p.waitForTimeout(150);
    await p.mouse.up();
  };
  await drag("a1", "a3", true); // reorder down within Left
  await expect.poll(() => titles("Left")).toEqual(["a2", "a3", "a1"]);
  await drag("a2", "b1"); // across lists, before b1
  await expect.poll(() => titles("Right")).toEqual(["a2", "b1"]);
  await expect.poll(() => titles("Left")).toEqual(["a3", "a1"]);

  // Orphaned accepts nothing (§3.6): delete Right, then try to drop a card onto its orphans.
  await p.getByRole("button", { name: "Delete list Right" }).click();
  await expect.poll(() => titles("Orphaned")).toEqual(["a2", "b1"]);
  await drag("a3", "b1");
  await expect.poll(() => titles("Left")).toEqual(["a3", "a1"]);
  await expect.poll(() => titles("Orphaned")).toEqual(["a2", "b1"]);
  await settled(p);
  await a.close();
});

test("presence per connection: a user with two tabs stays present when one closes (amendment 22)", async ({ browser }) => {
  const a = await browser.newContext(), b = await browser.newContext();
  await signUp(a, "Ann Watcher");
  const two = await signUp(b, "Tia Twotabs");
  const board = await createBoard(a, "Two tabs");
  await addMember(a, board, two.email);
  const pa = await a.newPage();
  await openBoard(pa, board);
  const tab1 = await b.newPage(), tab2 = await b.newPage();
  await openBoard(tab1, board);
  await openBoard(tab2, board);
  await expect(pa.locator(".who .avatar")).toHaveText(["TT"]);
  await tab1.close();
  // Its "left" arrives at once; Tia is still here on her other tab, before the other tab's
  // next heartbeat could mask a wrong removal, and after it.
  await pa.waitForTimeout(1_500);
  await expect(pa.locator(".who .avatar")).toHaveText(["TT"], { timeout: 1 });
  await pa.waitForTimeout(10_500);
  await expect(pa.locator(".who .avatar")).toHaveText(["TT"]);
  await tab2.close();
  await expect(pa.locator(".who .avatar")).toHaveCount(0);
  await a.close();
  await b.close();
});
