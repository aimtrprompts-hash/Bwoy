// The bridge to the Fire TV (Android) app that hosts Hearth.
//
// On an LG TV Hearth talks to webOS through the Luna bridge (luna.ts) and the Homebrew Channel's root shell.
// On a Fire TV it runs inside a WebView, and the app around it gives the page one object, `HearthAndroid`, with a
// single method: call(method, argsJson) -> a JSON string {"ok":true,"data":...} or {"ok":false,"error":"..."}.
// The methods are listed in android/app/src/main/java/com/hearth/firetv/Bridge.java.
//
// This file must not import luna.ts or apps.ts: other modules import it, and their tests replace those two.

export interface NativeBridge {
	call (method: string, args: string): string;
}

declare global {
	interface Window {
		HearthAndroid?: NativeBridge;
	}
}

/** True inside the Fire TV app. */
export function isAndroid (): boolean {
	return typeof window !== 'undefined' && !!window.HearthAndroid && typeof window.HearthAndroid.call === 'function';
}

/** Calls the app and returns the parsed answer. Throws when the app is missing or answers with an error. */
export function nativeCall<T = unknown> (method: string, args: object = {}): T {
	const bridge = typeof window !== 'undefined' ? window.HearthAndroid : undefined;
	if (!bridge) throw new Error('Not running on Fire TV');
	const raw = bridge.call(method, JSON.stringify(args));
	let reply: {ok?: boolean; data?: T; error?: string};
	try { reply = JSON.parse(raw); }
	catch { throw new Error(`${method}: the app gave an unreadable answer`); }
	if (!reply || reply.ok !== true) throw new Error((reply && reply.error) || `${method} failed`);
	return reply.data as T;
}

/** The same call as a promise (it rejects instead of throwing). */
export function nativeAsync<T = unknown> (method: string, args: object = {}): Promise<T> {
	return new Promise<T>((resolve, reject) => {
		try { resolve(nativeCall<T>(method, args)); }
		catch (e) { reject(e); }
	});
}

// ---------- shapes the app answers with ----------

export interface NativeApp {
	/** the package name, e.g. com.netflix.ninja */
	id: string;
	title: string;
	/** address of the app's icon (served by the app itself) */
	icon: string;
	/** false for apps that came with the Fire TV: the system will not remove them */
	removable: boolean;
}

export interface NativeEntry {
	name: string;
	isDir: boolean;
}

export interface NativeRoot {
	label: string;
	path: string;
}

/** What the app knows about how Hearth is wired into the Fire TV. */
export interface FireStatus {
	/** the "open Hearth when Home is pressed" service is switched on in the system's accessibility settings */
	homeService: boolean;
	/** Hearth may switch that service on by itself (the adb permission was granted once) */
	canEnable: boolean;
	/** start Hearth when the Fire TV starts */
	boot: boolean;
	/** Amazon's own home app has been disabled (adb), so Hearth is the only home app */
	amazonHomeDisabled: boolean;
	/** the system currently picks Hearth for the Home button */
	isDefaultHome: boolean;
	/** a Home-key request is being answered with Hearth right now (service on, or Amazon's home disabled) */
	takesOverHome: boolean;
	/** storage permission for pictures was granted */
	storage: boolean;
	fireOs: string;
	device: string;
	ip: string;
	/** the one-line adb command that lets Hearth switch the service on and off itself */
	grantCommand: string;
}

declare global {
	interface Window {
		/** called by the app for keys the web view would not pass on (Back) */
		__hearthKey?: (type: string, keyCode: number, repeat: boolean) => void;
	}
}

/** The app sends the remote's Back button here (a web view never shows it to the page). It becomes the same
 *  key event a webOS TV sends (code 461), so the rest of Hearth needs no change. */
export function installNativeKeys (): void {
	window.__hearthKey = (type, keyCode, repeat) => {
		const event = document.createEvent('Event');
		event.initEvent(type, true, true);
		Object.defineProperty(event, 'keyCode', {get: () => keyCode});
		Object.defineProperty(event, 'which', {get: () => keyCode});
		Object.defineProperty(event, 'repeat', {get: () => repeat});
		(document.activeElement || window).dispatchEvent(event);
	};
}

/** The address of a picture on the Fire TV's storage, served by the app. */
export function androidFileUrl (path: string): string {
	return `${location.origin}/__file?p=${encodeURIComponent(path)}`;
}
