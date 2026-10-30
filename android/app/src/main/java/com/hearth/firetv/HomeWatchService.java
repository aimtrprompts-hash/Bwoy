package com.hearth.firetv;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

import java.util.Set;

/**
 * Opens Hearth whenever a home screen other than Hearth comes to the front. Fire OS does not let an app take the Home
 * button over, so this watches for Amazon's home app appearing (Home pressed, Back out of an app, start-up) and puts
 * Hearth in front of it. It looks only at which window is in front: it cannot read anything on the screen.
 *
 * Which apps count as "a home screen" is asked of the system (every app that offers itself as a home screen except
 * Hearth), plus Amazon's own name, so it does not depend on one guess. It stays out of the way for a while after
 * Hearth's own "Fire TV Home" tile was used. What it saw is kept for the "Home-button test" in Hearth's settings.
 */
public class HomeWatchService extends AccessibilityService {
    private static final long HOME_LIST_TTL_MS = 60000;

    private long lastOpen = 0;
    private Set<String> homePackages;
    private long homePackagesAt = 0;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        HomeState.serviceConnected();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        HomeState.serviceDisconnected();
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        String name = pkg.toString();
        CharSequence cls = event.getClassName();
        HomeState.noteWindow(name, cls == null ? null : cls.toString());

        if (name.equals(getPackageName())) return;
        if (!isHomeApp(name)) return;
        if (HomeState.standingAside()) return;

        long now = SystemClock.elapsedRealtime();
        if (now - lastOpen < 400) return;   // one event per screen change is enough
        lastOpen = now;

        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(intent);
            HomeState.noteRedirect();
        } catch (RuntimeException ignored) {
            // nothing sensible to do: the next Home press tries again
        }
    }

    private boolean isHomeApp(String pkg) {
        long now = SystemClock.elapsedRealtime();
        if (homePackages == null || now - homePackagesAt > HOME_LIST_TTL_MS) {
            homePackages = HomeState.homePackages(this);
            homePackagesAt = now;
        }
        return homePackages.contains(pkg);
    }

    @Override
    public void onInterrupt() {
        // nothing to interrupt
    }
}
