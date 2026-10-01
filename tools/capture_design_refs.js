const { chromium } = require('playwright');
const path = require('path');

(async () => {

const jobs = [
  ['veykad-product-concept-preview.html', 'create.png'],
  ['veykad-edits-section-preview.html', 'edits.png'],
  ['veykad-settings-section-preview.html', 'settings.png'],
];

const sourceDir = 'C:/Users/rexar/.codex/visualizations/2026/09/24/01a0d387-d682-7890-9493-c5df95063e47';
const outputDir = path.resolve('docs/design/approved');
const browser = await chromium.launch({ headless: true, executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe' });

for (const [input, output] of jobs) {
  const page = await browser.newPage({ viewport: { width: 900, height: 1100 }, colorScheme: 'light', deviceScaleFactor: 2 });
  await page.goto(`file:///${path.join(sourceDir, input).replace(/\\/g, '/')}`);
  const device = page.frameLocator('#codex-visualization').locator('.device').first();
  await device.waitFor({ state: 'visible' });
  await device.screenshot({ path: path.join(outputDir, output) });
  await page.close();
}

const board = await browser.newPage({ viewport: { width: 1900, height: 1100 }, colorScheme: 'light', deviceScaleFactor: 1 });
await board.goto(`file:///${path.join(outputDir, 'additional-board.html').replace(/\\/g, '/')}`);
await board.screenshot({ path: path.join(outputDir, 'additional-board.png'), fullPage: true });
await board.close();

await browser.close();
})();
