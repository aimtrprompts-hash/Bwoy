// The TV's 12/24-hour clock setting (Settings → General → Time).
// webOSSystem.timeFormat is empty on some firmware, so ask the system service instead.

import {LUNA} from '../config/constants';
import {isWebOS, lunaCall, type LunaResponse} from './luna';
import type {TimeFormat} from './clock';
import {isAndroid, nativeAsync} from './android';

interface PreferencesResponse extends LunaResponse {
	timeFormat?: string;     // "HH12" | "HH24"
}

/** Used off-webOS and if the TV doesn't answer. */
export const DEFAULT_TIME_FORMAT: TimeFormat = '24h';

export function getTimeFormat (): Promise<TimeFormat> {
	if (isAndroid()) return nativeAsync<boolean>('is24h').then((h24): TimeFormat => (h24 ? '24h' : '12h'), () => DEFAULT_TIME_FORMAT);
	if (!isWebOS()) return Promise.resolve(DEFAULT_TIME_FORMAT);
	return lunaCall<PreferencesResponse>(LUNA.preferences, {keys: ['timeFormat']})
		.then((r): TimeFormat => (r.timeFormat === 'HH12' ? '12h' : r.timeFormat === 'HH24' ? '24h' : DEFAULT_TIME_FORMAT))
		.catch(() => DEFAULT_TIME_FORMAT);
}
