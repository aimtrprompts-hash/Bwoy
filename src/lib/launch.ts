import {LUNA, SYSTEM_APP} from '../config/constants';
import {lunaCall} from './luna';
import {isAndroid, nativeAsync} from './android';

/** Launch an app or switch to an input (HDMI inputs are apps too: com.webos.app.hdmiN). */
export function launchApp (id: string, params: object = {}): Promise<void> {
	if (isAndroid()) return nativeAsync('launch', {id}).then(() => undefined);
	return lunaCall(LUNA.launch, {id, params}).then(() => undefined);
}

/** Bring up LG's own home screen. */
export function launchLgHome (): Promise<void> {
	// On a Fire TV this is Amazon's own home screen (Hearth stands aside for a while so it can be seen)
	if (isAndroid()) return nativeAsync('fireHome').then(() => undefined);
	return launchApp(SYSTEM_APP.lgHome);
}

/** Whether an app with this ID exists on the TV (permitted from a web app, unlike listing). */
export function appExists (id: string): Promise<boolean> {
	if (isAndroid()) return nativeAsync<boolean>('exists', {id}).catch(() => false);
	return lunaCall<{exist?: boolean}>(LUNA.appLoadStatus, {appId: id})
		.then((r) => r.exist === true)
		.catch(() => false);
}

/** Open the TV's own Settings app: the first of its known IDs that exists on this TV. When none can be
 *  confirmed (the check itself may be refused on some TVs) the most likely ID is launched anyway. */
export function launchSystemSettings (): Promise<void> {
	if (isAndroid()) return nativeAsync('systemSettings').then(() => undefined);
	const ids = SYSTEM_APP.systemSettings;
	const next = (i: number): Promise<void> => {
		if (i >= ids.length) return launchApp(ids[0]);
		return appExists(ids[i]).then((found) => (found ? launchApp(ids[i]) : next(i + 1)));
	};
	return next(0);
}
