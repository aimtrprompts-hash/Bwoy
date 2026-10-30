// Fit the 1920 x 1080 page to the screen when the web view does not do it by itself.
//
// Hearth is laid out for a 1920 x 1080 screen. The Fire TV app asks its web view to scale that to the real screen (the
// page's viewport tag, with "wide viewport" on). If a web view ignores that, the page would be drawn at the screen's
// own number of CSS pixels (say 960 or 1280 wide) and everything would look too big. This notices that and scales the
// page instead. When the viewport is already 1920 wide, nothing is changed.

export const DESIGN_WIDTH = 1920;
export const DESIGN_HEIGHT = 1080;

/** The scale that makes a 1920 x 1080 page fit a viewport, or null when the viewport already is that size
 *  (within 1 %: the web view did the scaling itself). */
export function fitScale (viewWidth: number, viewHeight: number): number | null {
	if (!(viewWidth > 0) || !(viewHeight > 0)) return null;
	if (Math.abs(viewWidth - DESIGN_WIDTH) <= DESIGN_WIDTH * 0.01) return null;
	return Math.min(viewWidth / DESIGN_WIDTH, viewHeight / DESIGN_HEIGHT);
}

/** Applies the scale now and whenever the window changes size. Returns the scale in use (null: none needed). */
export function installFit (): number | null {
	const apply = (): number | null => {
		const scale = fitScale(window.innerWidth, window.innerHeight);
		const root = document.documentElement;
		const body = document.body;
		if (scale === null) {
			root.style.overflow = '';
			body.style.width = '';
			body.style.height = '';
			body.style.transform = '';
			body.style.transformOrigin = '';
			return null;
		}
		root.style.overflow = 'hidden';
		body.style.width = `${DESIGN_WIDTH}px`;
		body.style.height = `${DESIGN_HEIGHT}px`;
		body.style.transformOrigin = '0 0';
		body.style.transform = `scale(${scale})`;
		return scale;
	};
	window.addEventListener('resize', apply);
	return apply();
}
