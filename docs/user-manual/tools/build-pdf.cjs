// Builds docs/user-manual/user-manual.pdf from docs/user-manual/README.md: GitHub's Markdown
// renderer (`gh api markdown`, mode gfm, so the PDF reads as GitHub shows the page), then
// Playwright Chromium's page.pdf() on A4, with page numbers and an outline.
const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { REPO, MANUAL, launch, main } = require('./lib.cjs');

/** Links to other files of the repository point at GitHub, where the manual is read. */
const GITHUB = 'https://github.com/dromero001/flight-aggregator/blob/main/';
const SOURCE = path.join(MANUAL, 'README.md');
const TARGET = path.join(MANUAL, 'user-manual.pdf');

/** GitHub's id for a heading: lower case, punctuation dropped, spaces as hyphens. */
function slug(text) {
  return text.trim().toLowerCase().replace(/[^\p{L}\p{N}\- ]/gu, '').replace(/ /g, '-');
}

function render() {
  const html = execFileSync('gh', ['api', 'markdown', '-f', 'mode=gfm', '-F', `text=@${SOURCE}`], { encoding: 'utf8' });
  return (
    html
      // The API gives headings no id: each gets GitHub's, which the Contents and other links name.
      .replace(/<h([1-4])>(.*?)<\/h\1>/g, (_, level, inner) => {
        const text = inner.replace(/<[^>]+>/g, '').replace(/&amp;/g, '&');
        return `<h${level} id="${slug(text)}">${inner}</h${level}>`;
      })
      // Relative links out of the manual's folder point at the file on GitHub.
      .replace(/href="((?:\.\.\/)+[^"#]*)(#[^"]*)?"/g, (_, relative, hash = '') => {
        const target = path.relative(REPO, path.resolve(MANUAL, relative)).split(path.sep).join('/');
        return `href="${GITHUB}${target}${hash}"`;
      })
      .replace(/href="(tools\/[^"]*|user-manual\.pdf)"/g, (_, file) => `href="${GITHUB}docs/user-manual/${file}"`)
      // GitHub wraps each image in a link to itself: keep the image only.
      .replace(/<a target="_blank" rel="noopener noreferrer" href="[^"]*images\/[^"]*">(<img [^>]*>)<\/a>/g, '$1')
  );
}

const CSS = `
@page { size: A4; }
body { font-family: -apple-system, "Helvetica Neue", Arial, sans-serif; font-size: 10.5pt; line-height: 1.5; color: #1b2a41; }
h1 { font-size: 22pt; margin: 0 0 8pt; border-bottom: 2px solid #1b2a41; padding-bottom: 4pt; }
h2 { font-size: 16pt; margin: 18pt 0 6pt; border-bottom: 1px solid #c9d1da; padding-bottom: 3pt; break-before: page; }
h2#contents, h2#before-you-start { break-before: auto; }
h3 { font-size: 12.5pt; margin: 14pt 0 4pt; }
h2, h3 { break-after: avoid; }
a { color: #1d5fa8; text-decoration: none; }
code { font-family: Menlo, Consolas, monospace; font-size: 9pt; background: #f0f3f6; padding: 0 3px; border-radius: 3px; }
pre { background: #f0f3f6; padding: 6pt; border-radius: 3px; white-space: pre-wrap; }
pre code { padding: 0; }
table { border-collapse: collapse; width: 100%; margin: 6pt 0; font-size: 9.5pt; }
tr { break-inside: avoid; }
th, td { border: 1px solid #c9d1da; padding: 4pt 6pt; text-align: left; vertical-align: top; }
th { background: #f0f3f6; }
p:has(> img) { margin: 8pt 0 2pt; break-inside: avoid; break-after: avoid; text-align: center; }
img { max-width: 88%; max-height: 105mm; border: 1px solid #c9d1da; }
img[src*="phone"] { max-width: 62mm; }
img[src*="swagger-search-tried"] { max-height: 150mm; }
img[src*="authorize"], img[src*="sign-in"] { max-width: 120mm; }
p:has(> img) + p { margin-top: 0; text-align: center; font-size: 9pt; color: #5c6b7a; }
`;

const FOOTER =
  '<div style="font-family:Helvetica,Arial,sans-serif;font-size:8pt;color:#5c6b7a;width:100%;padding:0 16mm;' +
  'display:flex;justify-content:space-between"><span>Flight Aggregator user manual, v1.2.0</span>' +
  '<span><span class="pageNumber"></span> / <span class="totalPages"></span></span></div>';

main(async () => {
  const base = pathToFileURL(MANUAL + path.sep).href;
  const document = `<!doctype html><html lang="en"><head><meta charset="utf-8"><title>Flight Aggregator user manual</title>
<base href="${base}"><style>${CSS}</style></head><body>${render()}</body></html>`;
  const folder = fs.mkdtempSync(path.join(os.tmpdir(), 'user-manual-'));
  const htmlFile = path.join(folder, 'user-manual.html');
  fs.writeFileSync(htmlFile, document);

  const browser = await launch();
  const page = await browser.newPage();
  await page.goto(pathToFileURL(htmlFile).href, { waitUntil: 'load' });
  await page.pdf({
    path: TARGET,
    format: 'A4',
    printBackground: true,
    displayHeaderFooter: true,
    headerTemplate: '<div></div>',
    footerTemplate: FOOTER,
    margin: { top: '18mm', bottom: '20mm', left: '16mm', right: '16mm' },
    outline: true,
    tagged: true,
  });
  await browser.close();
  fs.rmSync(folder, { recursive: true, force: true });
  console.log('written', path.relative(REPO, TARGET));
});
