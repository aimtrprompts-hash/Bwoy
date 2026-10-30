// Tests for the Fire TV side of the web UI (src/lib/android.ts and the places that use it).   Run: npm test
// A fake `window.HearthAndroid` plays the part of the Android app; nothing here needs a device.
import {build} from 'esbuild';

const dir = new URL('../node_modules/', import.meta.url).pathname;
const src = (f) => new URL('../src/' + f, import.meta.url).pathname;

// lib/apps.ts, lib/luna.ts and the shell are replaced by stubs: only the Fire TV branches are under test.
const stubs = {
  './apps': 'export const exec = () => Promise.reject(new Error("no shell")); export const shellQuote = (v) => v;',
  './luna': 'export const isWebOS = () => false; export const lunaCall = () => Promise.reject(new Error("not webos"));'
};
async function load (entry, name) {
  const out = dir + `.cache-android-${name}.mjs`;
  await build({
    entryPoints: [src(entry)], bundle: true, format: 'esm', outfile: out, platform: 'node', logLevel: 'error',
    plugins: [{name: 'stubs', setup (b) {
      b.onResolve({filter: /^\.\/(apps|luna)$/}, (a) => ({path: a.path, namespace: 'stub'}));
      b.onLoad({filter: /.*/, namespace: 'stub'}, (a) => ({contents: stubs[a.path], loader: 'js'}));
    }}]
  });
  return import(out);
}

let fail = 0;
const eq = (a, b, m) => { const ok = JSON.stringify(a) === JSON.stringify(b); if (!ok) { fail++; console.log('FAIL', m, JSON.stringify(a), '!=', JSON.stringify(b)); } else console.log('ok  ', m); };

// ---- the fake app ----
const calls = [];
const answers = {};            // method -> value | function(args)
const fake = {
  call (method, json) {
    const args = JSON.parse(json || '{}');
    calls.push({method, args});
    if (!(method in answers)) return JSON.stringify({ok: false, error: 'Unknown call: ' + method});
    const a = answers[method];
    try { return JSON.stringify({ok: true, data: typeof a === 'function' ? a(args) : a}); }
    catch (e) { return JSON.stringify({ok: false, error: e.message}); }
  }
};
const reset = () => { calls.length = 0; for (const k of Object.keys(answers)) delete answers[k]; };

const a = await load('lib/android.ts', 'core');

// off the Fire TV
delete globalThis.window;
eq(a.isAndroid(), false, 'no window: not a Fire TV');
globalThis.window = {};
eq(a.isAndroid(), false, 'a window without the bridge: not a Fire TV');
let message = '';
try { a.nativeCall('listApps'); } catch (e) { message = e.message; }
eq(message, 'Not running on Fire TV', 'a call without the bridge says so');

// on the Fire TV
globalThis.window = {HearthAndroid: fake, location: {origin: 'https://hearth.local'}, setTimeout: (fn, ms) => setTimeout(fn, ms)};
globalThis.location = globalThis.window.location;
eq(a.isAndroid(), true, 'with the bridge: a Fire TV');
reset(); answers.exists = true;
eq(a.nativeCall('exists', {id: 'com.x'}), true, 'a call returns the data');
eq(calls[0], {method: 'exists', args: {id: 'com.x'}}, 'the arguments reach the app as JSON');
message = '';
try { a.nativeCall('nope'); } catch (e) { message = e.message; }
eq(message, 'Unknown call: nope', "the app's error text comes back");
globalThis.window.HearthAndroid = {call: () => 'not json'};
message = '';
try { a.nativeCall('x'); } catch (e) { message = e.message; }
eq(/unreadable/.test(message), true, 'an unreadable answer is an error, not a crash');
globalThis.window.HearthAndroid = fake;
let rejected = false;
await a.nativeAsync('nope').catch(() => { rejected = true; });
eq(rejected, true, 'nativeAsync rejects instead of throwing');
eq(a.androidFileUrl("/storage/USB 1/it's #1.jpg"), "https://hearth.local/__file?p=%2Fstorage%2FUSB%201%2Fit's%20%231.jpg", 'picture addresses are encoded');

// ---- launching ----
const launch = await load('lib/launch.ts', 'launch');
reset(); answers.launch = true; answers.fireHome = true; answers.systemSettings = true; answers.exists = (x) => x.id === 'com.netflix.ninja';
await launch.launchApp('com.netflix.ninja');
await launch.launchLgHome();
await launch.launchSystemSettings();
eq(calls.map((c) => c.method), ['launch', 'fireHome', 'systemSettings'], 'apps, the Fire TV home and the settings open through the app');
eq(calls[0].args, {id: 'com.netflix.ninja'}, 'the package name is sent');
eq(await launch.appExists('com.netflix.ninja'), true, 'an installed app exists');
eq(await launch.appExists('com.missing'), false, 'a missing app does not');
reset();
let refused = false;
await launch.launchApp('com.x').catch(() => { refused = true; });
eq(refused, true, 'when the app refuses to open one, the error reaches the page');

// ---- clock format ----
const tf = await load('lib/timeFormat.ts', 'time');
reset(); answers.is24h = false;
eq(await tf.getTimeFormat(), '12h', 'the Fire TV clock setting: 12-hour');
answers.is24h = true;
eq(await tf.getTimeFormat(), '24h', 'the Fire TV clock setting: 24-hour');
reset();
eq(await tf.getTimeFormat(), '24h', 'when the app cannot say, 24-hour');

// ---- the "about" line ----
const about = await load('lib/about.ts', 'about');
reset(); answers.info = {fireOs: '6.7.1.1', device: 'AFTMM', ip: '192.168.1.20'};
eq(await about.readVersions(), {fireOs: '6.7.1.1', device: 'AFTMM'}, 'versions come from the app');
eq(await about.readIp(), '192.168.1.20', 'the address comes from the app');
eq(about.aboutText({fireOs: '6.7.1.1', device: 'AFTMM', ip: '192.168.1.20'}, 'unknown', null, '1.2.0'), 'Hearth 1.2.0  ·  Fire OS 6.7.1.1  ·  AFTMM  ·  IP: 192.168.1.20', 'the line has no "Root:" part on a Fire TV');
eq(about.aboutText({fireOs: 'Android 7.1.2'}, 'unknown', null, '1.2.0'), 'Hearth 1.2.0  ·  Android 7.1.2', 'an Android version is not called Fire OS');
eq(/Root/.test(about.aboutText({webos: '7.0.0'}, 'unknown', null, '1.2.0')), true, 'the LG line still has its Root part');

// ---- pictures ----
const wp = await load('lib/wallpaper.ts', 'wallpaper');
eq(wp.entriesFromNative([
  {name: 'Zeta', isDir: true}, {name: 'alpha', isDir: true}, {name: '.hidden', isDir: true}, {name: '._a.jpg', isDir: false},
  {name: 'b.JPG', isDir: false}, {name: 'a.png', isDir: false}, {name: 'notes.txt', isDir: false}, {name: 'clip.mp4', isDir: false}, {name: 'sea.webp', isDir: false}
]), [{name: 'alpha', isDir: true}, {name: 'Zeta', isDir: true}, {name: 'a.png', isDir: false}, {name: 'b.JPG', isDir: false}, {name: 'sea.webp', isDir: false}], 'a Fire TV listing is filtered and sorted like the LG one');
reset(); answers.roots = [{label: 'USB drive', path: '/storage/1234-ABCD'}, {label: 'Internal storage', path: '/storage/emulated/0'}];
eq(await wp.listRoots(), answers.roots, 'USB drives and internal storage come from the app');
eq(wp.isInternalRoot({label: 'Internal storage'}), true, 'internal storage is told apart by its label');
eq(wp.isInternalRoot({label: 'USB drive'}), false, 'a USB drive is not internal storage');
reset(); answers.listDir = [{name: 'Holiday', isDir: true}, {name: 'x.jpg', isDir: false}, {name: 'readme.txt', isDir: false}];
eq(await wp.listDir('/storage/1234-ABCD'), [{name: 'Holiday', isDir: true}, {name: 'x.jpg', isDir: false}], 'listDir goes through the app');
eq(calls[0].args, {path: '/storage/1234-ABCD'}, 'the folder is sent');
// loading: size limits are checked before the picture is opened
reset(); answers.fileInfo = {size: 9 * 1024 * 1024};
let problem = '';
await wp.loadPicture('/storage/emulated/0/big.jpg').catch((e) => { problem = e.problem; });
eq(problem, 'too-large', 'a picture over 8 MB is refused before it is opened');
reset(); answers.fileInfo = {size: 0};
problem = '';
await wp.loadPicture('/storage/emulated/0/empty.jpg').catch((e) => { problem = e.problem; });
eq(problem, 'unreadable', 'an empty file is reported as unreadable');
reset();
problem = '';
await wp.loadPicture('/storage/emulated/0/gone.jpg').catch((e) => { problem = e.problem; });
eq(problem, 'unreadable', 'when the app cannot read the file, the picture is reported as unreadable');

// ---- uninstall ----
const un = await load('lib/uninstall.ts', 'uninstall');
eq(un.uninstallVerdict({id: 'com.plexapp.android', removable: true}), {ok: true, reason: ''}, 'an app the person installed may be removed');
eq(un.uninstallVerdict({id: 'com.amazon.tv.settings', removable: false}).ok, false, 'an app that came with the Fire TV may not');
eq(un.uninstallVerdict({id: 'bad id', removable: true}).ok, false, 'a bad package name is refused');
// the system asks the person; the page waits for the answer
reset(); answers.uninstall = true;
let step = 0;
answers.uninstallState = () => (++step < 3 ? 'pending' : 'removed');
eq(await un.uninstallApp('com.plexapp.android'), 'removed', 'removed once the system says so');
eq(calls.filter((c) => c.method === 'uninstallState').length, 3, 'the page asks again while the answer is pending');
reset(); answers.uninstall = true; answers.uninstallState = 'cancelled';
eq(await un.uninstallApp('com.plexapp.android'), 'still-installed', 'a cancelled uninstall is reported');
reset();
eq(await un.uninstallApp('com.plexapp.android'), 'failed', 'when the app will not start it, it failed');

// ---- fitting the page to the screen ----
const fit = await load('lib/fit.ts', 'fit');
eq(fit.fitScale(1920, 1080), null, 'a 1920 x 1080 viewport needs no scaling');
eq(fit.fitScale(1930, 1086), null, 'a viewport within 1 % needs no scaling');
eq(fit.fitScale(960, 540), 0.5, 'a 960 x 540 viewport scales the page by 0.5');
eq(fit.fitScale(1280, 720), 1280 / 1920, 'a 1280 x 720 viewport scales the page by two thirds');
eq(fit.fitScale(1280, 540), 0.5, 'a wide short viewport fits by its height');
eq(fit.fitScale(3840, 2160), 2, 'a 4K viewport scales the page up by 2');
eq(fit.fitScale(0, 0), null, 'an unknown size changes nothing');

// ---- the settings rows ----
const prefs = await load('lib/prefs.ts', 'prefs');
const storage = await load('lib/storage.ts', 'storage');
globalThis.localStorage = {getItem: () => null, setItem: () => undefined};
const config = storage.loadConfig();
const ids = (list) => list.map((o) => o.id);
const optionsFire = prefs.optionsFor('options', config, {homeHook: null, fire: {homeService: false, canEnable: false, boot: false, amazonHomeDisabled: false, isDefaultHome: false, takesOverHome: false, storage: true, fireOs: '6.7.1.1', device: 'AFTMM', ip: '', grantCommand: ''}});
eq(ids(optionsFire).slice(0, 3), ['fireHome', 'fireBoot', 'fireTest'], 'on a Fire TV the Options tab starts with the Home, start-up and Home-test rows');
eq(optionsFire[2].valueText, 'Check', 'the Home-test row has a Check button');
eq(/press Home on the remote/.test(optionsFire[2].description), true, 'the Home-test row says what to do first');
const tested = prefs.optionsFor('options', config, {homeHook: null, fireTest: 'Helper running now: yes. RESULT: working.'});
eq(tested.filter((o) => o.id === 'fireTest')[0].description, 'Helper running now: yes. RESULT: working.', 'the answer of the Home test replaces the description');
eq(ids(optionsFire).some((id) => ['takeover', 'replaceHome', 'tvInput', 'lgLogo', 'tvApps'].indexOf(id) !== -1), false, 'the LG-only rows are not shown on a Fire TV');
eq(/adb shell pm grant com\.hearth\.firetv android\.permission\.WRITE_SECURE_SETTINGS/.test(optionsFire[0].description), true, 'the Home row says how to finish the one setup step');
eq(optionsFire[0].valueText, 'Off', 'the Home row reads Off when the helper is off');
const on = prefs.optionsFor('options', config, {homeHook: null, fire: {homeService: true, canEnable: true, boot: true, amazonHomeDisabled: false, isDefaultHome: false, takesOverHome: true, storage: true, fireOs: '', device: '', ip: '', grantCommand: ''}});
eq([on[0].valueText, on[1].valueText], ['On', 'On'], 'both rows read On when they are on');
eq(prefs.optionsFor('options', config, {homeHook: null, fire: {homeService: false, canEnable: false, boot: false, amazonHomeDisabled: true, isDefaultHome: true, takesOverHome: true, storage: true, fireOs: '', device: '', ip: '', grantCommand: ''}})[0].valueText, 'On (Amazon home off)', 'with Amazon\'s home switched off the row says so');
eq(optionsFire.length > 0 && ids(prefs.optionsFor('look', config)).indexOf('recentsW') === -1, true, 'the Recent apps panel sizes are hidden on a Fire TV');
eq(prefs.optionsFor('look', config).filter((o) => o.id === 'showLgHome')[0].label, 'Show the Fire TV Home tile', 'the LG Home tile is called the Fire TV Home tile');
// and on an LG TV nothing changes
globalThis.window = {};
const lg = prefs.optionsFor('options', config, {homeHook: null});
eq(ids(lg).indexOf('fireHome') === -1 && ids(lg).indexOf('takeover') !== -1 && ids(lg).indexOf('tvInput') !== -1, true, 'off a Fire TV the LG rows are shown and the Fire TV rows are not');
eq(prefs.optionsFor('look', config).filter((o) => o.id === 'showLgHome')[0].label, 'Show the LG Home tile', 'the LG wording is kept on an LG TV');

process.exit(fail ? 1 : 0);
