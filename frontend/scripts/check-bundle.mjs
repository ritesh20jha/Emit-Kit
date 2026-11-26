// Measures the JavaScript the app loads before first render and fails the build over budget (spec §4.6: < 250 KB gzip).
// Initial chunks are the ones dist/index.html references: the entry script and its modulepreload links. Lazy route and
// editor chunks are not counted, because the browser fetches them only when a route needs them.
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { gzipSync } from "node:zlib";

const BUDGET_BYTES = 250 * 1024;
const distDir = join(dirname(fileURLToPath(import.meta.url)), "..", "dist");
const html = readFileSync(join(distDir, "index.html"), "utf8");

const references = [...html.matchAll(/<script[^>]*type="module"[^>]*src="([^"]+)"|<link[^>]*rel="modulepreload"[^>]*href="([^"]+)"/g)];
const files = [...new Set(references.map((match) => match[1] ?? match[2]))];
if (files.length === 0) {
  console.error("No initial script found in dist/index.html. Run the build first.");
  process.exit(1);
}

let total = 0;
for (const file of files) {
  const gzipped = gzipSync(readFileSync(join(distDir, file))).length;
  total += gzipped;
  console.log(`${String(gzipped).padStart(8)} B gzip  ${file}`);
}

const kb = (total / 1024).toFixed(2);
console.log(`initial JS: ${total} B gzip (${kb} KB) across ${files.length} chunks; budget 250 KB`);
if (total >= BUDGET_BYTES) {
  console.error("Initial JS is over the 250 KB gzip budget.");
  process.exit(1);
}
