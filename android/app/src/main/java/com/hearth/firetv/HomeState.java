package com.hearth.firetv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/** Small shared facts: the "start at boot" switch, the short pause during which Amazon's home may be shown, and what the Home watcher has seen. */
final class HomeState {
    /** Amazon's home app on a Fire TV. */
    static final String AMAZON_HOME = "com.amazon.tv.launcher";

    private static final String PREFS = "hearth";
    private static final String KEY_BOOT = "boot";
    private static final String KEY_ASKED_STORAGE = "askedStorage";
    private static final int RECENT_MAX = 6;

    /** SystemClock.elapsedRealtime() until which the Home watcher leaves Amazon's home alone. */
    private static volatile long standAsideUntil = 0;

    // what the Home watcher has seen (shown by "Home-button test" in Settings)
    private static volatile long serviceConnectedAt = 0;
    private static volatile long lastRedirectAt = 0;
    private static volatile int redirects = 0;
    private static final LinkedList<String> RECENT = new LinkedList<String>();

    private HomeState() {}

    /** The person chose "Fire TV Home" in Hearth: let Amazon's home show for a while. */
    static void standAside(long millis) {
        standAsideUntil = SystemClock.elapsedRealtime() + millis;
    }

    static boolean standingAside() {
        return SystemClock.elapsedRealtime() < standAsideUntil;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean bootEnabled(Context c) {
        return prefs(c).getBoolean(KEY_BOOT, false);
    }

    static void setBootEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_BOOT, on).apply();
    }

    static boolean askedStorage(Context c) {
        return prefs(c).getBoolean(KEY_ASKED_STORAGE, false);
    }

    static void setAskedStorage(Context c) {
        prefs(c).edit().putBoolean(KEY_ASKED_STORAGE, true).apply();
    }

    /** The accessibility service as the system names it, e.g. com.hearth.firetv/com.hearth.firetv.HomeWatchService. */
    static ComponentName service(Context c) {
        return new ComponentName(c, HomeWatchService.class);
    }

    /**
     * The apps the system offers as a home screen, other than Hearth: Amazon's, and any other the Fire TV lists.
     * Amazon's own name is always included, so the watcher works even if the system's answer is incomplete.
     */
    static Set<String> homePackages(Context c) {
        Set<String> found = new LinkedHashSet<String>();
        found.add(AMAZON_HOME);
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo ri : c.getPackageManager().queryIntentActivities(home, 0)) {
                if (ri.activityInfo == null || ri.activityInfo.packageName == null) continue;
                if (ri.activityInfo.packageName.equals(c.getPackageName())) continue;
                found.add(ri.activityInfo.packageName);
            }
        } catch (RuntimeException ignored) {
            // keep what is known
        }
        return found;
    }

    // ------------------------------------------------------------------ the watcher's notes

    static void serviceConnected() {
        serviceConnectedAt = SystemClock.elapsedRealtime();
    }

    static void serviceDisconnected() {
        serviceConnectedAt = 0;
    }

    static boolean serviceRunning() {
        return serviceConnectedAt != 0;
    }

    /** Notes the window that came to the front (newest first, no repeats in a row). */
    static void noteWindow(String pkg, String cls) {
        String entry = pkg == null ? "?" : pkg;
        if (cls != null && cls.length() > 0) {
            String shortName = cls.startsWith(entry) ? cls.substring(entry.length()) : cls;
            entry = entry + " / " + shortName;
        }
        synchronized (RECENT) {
            if (!RECENT.isEmpty() && RECENT.getFirst().equals(entry)) return;
            RECENT.addFirst(entry);
            while (RECENT.size() > RECENT_MAX) RECENT.removeLast();
        }
    }

    static List<String> recentWindows() {
        synchronized (RECENT) {
            return new ArrayList<String>(RECENT);
        }
    }

    static void noteRedirect() {
        redirects++;
        lastRedirectAt = SystemClock.elapsedRealtime();
    }

    static int redirectCount() {
        return redirects;
    }

    /** Seconds since Hearth last put itself in front of a home app, or -1 when it has not. */
    static long secondsSinceRedirect() {
        return lastRedirectAt == 0 ? -1 : (SystemClock.elapsedRealtime() - lastRedirectAt) / 1000;
    }
}
