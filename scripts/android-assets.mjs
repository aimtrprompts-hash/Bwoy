// Copies the built web UI (dist/) into the Fire TV app (android/app/src/main/assets/web).   Run: npm run build:firetv
// The webOS-only files (appinfo.json, the webOS 11 permission script, the webOS splash/large icon) are left out.
import {cpSync, existsSync, mkdirSync, rmSync, statSync} from 'node:fs';
import {join} from 'node:path';

const root = new URL('..', import.meta.url).pathname;
const from = join(root, 'dist');
const to = join(root, 'android/app/src/main/assets/web');
const SKIP = ['appinfo.json', 'tools', 'splash.png', 'largeIcon.png', 'icon.png'];

if (!existsSync(join(from, 'index.html'))) throw new Error('dist/ is missing: run `npm run build` first (or use `npm run build:firetv`)');
rmSync(to, {recursive: true, force: true});
mkdirSync(to, {recursive: true});
cpSync(from, to, {recursive: true, filter: (src) => !SKIP.some((name) => src === join(from, name))});
if (!existsSync(join(to, 'index.html')) || !existsSync(join(to, 'assets/app.js'))) throw new Error('copy failed: index.html or assets/app.js is missing');
console.log(`Fire TV assets ready: ${to} (index.html ${statSync(join(to, 'index.html')).size} bytes)`);
