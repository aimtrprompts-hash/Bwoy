import {render} from 'preact';
import {App} from './app';
import {loadConfig} from './lib/storage';
import {applyBackground} from './lib/backgrounds';
import {applyTokens, THEMES} from './theme/tokens';
import './theme/global.css';
import globalCss from './theme/global.css?raw';
import {LEGACY, startLegacyVars} from './lib/cssvars';
import {mark} from './lib/perfmarks';
import {installNativeKeys, isAndroid} from './lib/android';
import {installFit} from './lib/fit';
import {log} from './lib/log';

mark('script started');
if (isAndroid()) {
	installNativeKeys();
	// the web view normally scales the 1920 px page itself; if it did not, scale it here
	const scale = installFit();
	log(scale === null ? `Screen: ${window.innerWidth}x${window.innerHeight}, page scaled by the web view` : `Screen: ${window.innerWidth}x${window.innerHeight}, page scaled by Hearth (${scale.toFixed(3)})`);
}

if (LEGACY) startLegacyVars(globalCss);       // Chromium 38: custom properties are emulated from the stylesheet source
const startConfig = loadConfig();
applyTokens(startConfig.theme);       // theme first, so there is no flash of the wrong colours
applyBackground(startConfig.background, THEMES[startConfig.theme]);
render(<App />, document.getElementById('root')!);
mark('first render');
window.requestAnimationFrame(() => window.requestAnimationFrame(() => mark('first frame drawn')));
