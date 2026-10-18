// Renders the diagrams to SVG and both documents to PDF, in headless Chromium.
//   node docs/tools/render.mjs        (after python docs/tools/refs.py build)
// Uses the Playwright installed for web/; Mermaid and marked come from jsDelivr, pinned.
import { createRequire } from "node:module";
import { existsSync, unlinkSync, readFileSync, readdirSync, writeFileSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const DOCS = join(dirname(fileURLToPath(import.meta.url)), "..");
const { chromium } = createRequire(join(DOCS, "..", "web", "package.json"))("@playwright/test");
const MERMAID = "https://cdn.jsdelivr.net/npm/mermaid@10.9.1/dist/mermaid.min.js";
const MARKED = "https://cdn.jsdelivr.net/npm/marked@12.0.2/marked.min.js";

const browser = await chromium.launch();
const page = await browser.newPage();

// 1. Diagrams: each .mmd to an .svg next to it.
await page.setContent(`<html><body><script src="${MERMAID}"></script></body></html>`);
await page.waitForFunction(() => window.mermaid);
await page.evaluate(() => window.mermaid.initialize({ startOnLoad: false, theme: "neutral", securityLevel: "strict", flowchart: { htmlLabels: false }, sequence: { mirrorActors: false, messageFontSize: 17, actorFontSize: 17, noteFontSize: 16, width: 120, messageMargin: 30 } }));
const dir = join(DOCS, "diagrams");
for (const f of readdirSync(dir).filter((x) => x.endsWith(".mmd"))) {
  const src = readFileSync(join(dir, f), "utf8");
  const svg = await page.evaluate(async ([id, s]) => (await window.mermaid.render(id, s)).svg, [f.replace(/\W/g, "_"), src]);
  writeFileSync(join(dir, f.replace(/\.mmd$/, ".svg")), svg);
}
console.log("diagrams rendered");

// 2. PDFs.
const CSS = `
  @page { size: A4; margin: 16mm 14mm; }
  body { font: 10.5pt/1.5 "Segoe UI", system-ui, sans-serif; color: #1b1b1b; max-width: 100%; }
  h1 { font-size: 22pt; margin: 0 0 4pt; } h2 { font-size: 15pt; margin: 20pt 0 6pt; border-bottom: 1px solid #ccc; padding-bottom: 2pt; page-break-after: avoid; }
  h3 { font-size: 12pt; margin: 14pt 0 4pt; page-break-after: avoid; } h4 { font-size: 11pt; margin: 10pt 0 3pt; }
  table { border-collapse: collapse; width: 100%; margin: 6pt 0 10pt; font-size: 8.8pt; page-break-inside: auto; }
  tr { page-break-inside: avoid; } th, td { border: 1px solid #c8c8c8; padding: 3pt 5pt; vertical-align: top; text-align: left; overflow-wrap: break-word; hyphens: manual; }
  th { background: #f1f1ef; } code { font: 8.6pt Consolas, monospace; background: #f4f4f2; padding: 0 2pt; border-radius: 2pt; overflow-wrap: anywhere; }
  pre { background: #f4f4f2; padding: 6pt 8pt; font-size: 8.4pt; white-space: pre-wrap; page-break-inside: avoid; }
  pre code { background: none; padding: 0; } a { color: #1a55a8; text-decoration: none; }
  img { max-width: 100%; display: block; margin: 6pt auto 10pt; page-break-inside: avoid; }
  blockquote { border-left: 3px solid #bbb; margin: 6pt 0; padding: 2pt 10pt; color: #444; }
  li { margin: 1pt 0; }`;
mkdirSync(join(DOCS, "pdf"), { recursive: true });
for (const name of ["how-concord-was-built", "interview-guide"]) {
  if (!existsSync(join(DOCS, `${name}.md`))) continue;
  const md = readFileSync(join(DOCS, `${name}.md`), "utf8");
  // A file next to the document, so its relative image links (diagrams/*.svg) load.
  const html = join(DOCS, `.${name}.render.html`);
  writeFileSync(html, `<html><head><meta charset="utf-8"><style>${CSS}</style><script src="${MARKED}"></script></head><body><main id="m"></main></body></html>`);
  await page.goto(pathToFileURL(html).href);
  await page.waitForFunction(() => window.marked);
  await page.evaluate((text) => { document.getElementById("m").innerHTML = window.marked.parse(text); }, md);
  await page.waitForFunction(() => [...document.images].every((i) => i.complete && i.naturalWidth > 0), null, { timeout: 30000 });
  const broken = await page.evaluate(() => [...document.images].filter((i) => !i.naturalWidth).map((i) => i.src));
  if (broken.length) throw new Error(`images not loaded: ${broken.join(", ")}`);
  if (process.env.SHOT_DIR) { // visual check: every diagram and the first tables, as printed
    await page.emulateMedia({ media: "print" });
    await page.setViewportSize({ width: 794, height: 1123 });
    const els = await page.$$("img, table");
    let i = 0;
    for (const el of els.slice(0, 40)) await el.screenshot({ path: join(process.env.SHOT_DIR, `${name}-${String(++i).padStart(2, "0")}.png`) });
  }
  await page.pdf({ path: join(DOCS, "pdf", `${name}.pdf`), format: "A4", printBackground: true, margin: { top: "16mm", bottom: "16mm", left: "14mm", right: "14mm" },
    displayHeaderFooter: true, headerTemplate: "<span></span>", footerTemplate: `<div style="font-size:7pt;width:100%;text-align:center;color:#888"><span class="pageNumber"></span> / <span class="totalPages"></span></div>` });
  unlinkSync(html);
  console.log(`docs/pdf/${name}.pdf`);
}
await browser.close();
