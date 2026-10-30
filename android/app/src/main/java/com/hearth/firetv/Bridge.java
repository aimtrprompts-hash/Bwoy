package com.hearth.firetv;

import android.Manifest;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The one object the web page can call: window.HearthAndroid.call(method, argsJson) returns
 * {"ok":true,"data":...} or {"ok":false,"error":"..."}. The page side is src/lib/android.ts.
 */
public class Bridge {
    private static final String ADB_GRANT = "adb shell pm grant com.hearth.firetv android.permission.WRITE_SECURE_SETTINGS";

    /** Shown first (in this order) when the person has not arranged the row yet; everything else follows A to Z. */
    private static final String[] FIRST = {
            "com.netflix.ninja", "com.amazon.firebat", "com.amazon.firetv.youtube", "com.google.android.youtube.tv",
            "com.disney.disneyplus", "com.hulu.plus", "com.wbd.stream", "com.apple.atve.amazon.appletv",
            "com.plexapp.android", "org.xbmc.kodi", "com.spotify.tv.android", "tv.twitch.android.app",
            "com.amazon.avod.thirdpartyclient", "com.esaba.downloader"
    };

    private final MainActivity activity;
    private final PackageManager pm;
    /** package -> "pending" | "removed" | "cancelled" */
    private final Map<String, String> uninstalls = Collections.synchronizedMap(new HashMap<String, String>());

    Bridge(MainActivity activity) {
        this.activity = activity;
        this.pm = activity.getPackageManager();
    }

    // ------------------------------------------------------------------ entry point

    @JavascriptInterface
    public String call(String method, String argsJson) {
        try {
            JSONObject args = (argsJson == null || argsJson.length() == 0) ? new JSONObject() : new JSONObject(argsJson);
            switch (method) {
                case "listApps": return ok(listApps());
                case "launch": return launch(args.getString("id"));
                case "exists": return ok(packageExists(args.getString("id")));
                case "fireHome": return fireHome();
                case "systemSettings": return systemSettings();
                case "uninstall": return uninstall(args.getString("id"));
                case "uninstallState": return ok(uninstallState(args.getString("id")));
                case "is24h": return ok(DateFormat.is24HourFormat(activity));
                case "info": return ok(info());
                case "roots": return ok(roots());
                case "listDir": return listDir(args.getString("path"));
                case "fileInfo": return fileInfo(args.getString("path"));
                case "homeStatus": return ok(homeStatus());
                case "setHomeTakeover": return setHomeTakeover(args.optBoolean("on", false));
                case "homeTest": return ok(homeTest());
                case "setBoot": HomeState.setBootEnabled(activity, args.optBoolean("on", false)); return ok(true);
                case "openAccessibility": return openAccessibility();
                default: return err("Unknown call: " + method);
            }
        } catch (Exception e) {
            return err(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private static String ok(Object data) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("data", data == null ? JSONObject.NULL : data);
            return o.toString();
        } catch (JSONException e) {
            return "{\"ok\":false,\"error\":\"json\"}";
        }
    }

    private static String err(String message) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("error", message);
            return o.toString();
        } catch (JSONException e) {
            return "{\"ok\":false,\"error\":\"error\"}";
        }
    }

    // ------------------------------------------------------------------ apps

    private JSONArray listApps() throws JSONException {
        Intent leanback = new Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.LEANBACK_LAUNCHER");
        Intent phone = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> found = new ArrayList<ResolveInfo>(pm.queryIntentActivities(leanback, 0));
        found.addAll(pm.queryIntentActivities(phone, 0));

        Map<String, JSONObject> byPackage = new LinkedHashMap<String, JSONObject>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg == null || pkg.equals(activity.getPackageName()) || byPackage.containsKey(pkg)) continue;
            CharSequence label = ri.loadLabel(pm);
            long updated = 0;
            try {
                PackageInfo pi = pm.getPackageInfo(pkg, 0);
                updated = pi.lastUpdateTime;
            } catch (PackageManager.NameNotFoundException ignored) {
                // listed a moment ago and gone again: skip it
                continue;
            }
            boolean system = (ri.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            JSONObject app = new JSONObject();
            app.put("id", pkg);
            app.put("title", label == null ? pkg : label.toString());
            app.put("icon", "https://" + WebHost.HOST + "/__icon/" + pkg + "?v=" + updated);
            app.put("removable", !system);
            byPackage.put(pkg, app);
        }

        List<JSONObject> apps = new ArrayList<JSONObject>(byPackage.values());
        Collections.sort(apps, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                int ra = rank(a.optString("id"));
                int rb = rank(b.optString("id"));
                if (ra != rb) return ra < rb ? -1 : 1;
                return a.optString("title").toLowerCase(Locale.ROOT).compareTo(b.optString("title").toLowerCase(Locale.ROOT));
            }
        });
        JSONArray out = new JSONArray();
        for (JSONObject a : apps) out.put(a);
        return out;
    }

    private static int rank(String pkg) {
        for (int i = 0; i < FIRST.length; i++) if (FIRST[i].equals(pkg)) return i;
        return FIRST.length;
    }

    private boolean packageExists(String id) {
        try {
            pm.getPackageInfo(id, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private String launch(String id) {
        Intent intent = pm.getLeanbackLaunchIntentForPackage(id);
        if (intent == null) intent = pm.getLaunchIntentForPackage(id);
        if (intent == null) return err("That app has no screen to open");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startFromBridge(intent);
        return ok(true);
    }

    // ------------------------------------------------------------------ Amazon's home, the system settings

    private ComponentName amazonHome() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : pm.queryIntentActivities(home, 0)) {
            if (ri.activityInfo != null && HomeState.AMAZON_HOME.equals(ri.activityInfo.packageName)) {
                return new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
            }
        }
        return null;
    }

    private String fireHome() {
        ComponentName target = amazonHome();
        if (target == null) return err("The Fire TV home was not found");
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        intent.setComponent(target);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        HomeState.standAside(30000);       // Hearth's Home watcher lets Amazon's home show for a while
        activity.startFromBridge(intent);
        return ok(true);
    }

    private String systemSettings() {
        Intent settings = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ResolveInfo ri = pm.resolveActivity(settings, 0);
        if (ri == null || ri.activityInfo == null) {
            // fall back to the Fire TV's own settings app, found through its launcher entry
            Intent leanback = new Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.LEANBACK_LAUNCHER");
            for (ResolveInfo candidate : pm.queryIntentActivities(leanback, 0)) {
                if (candidate.activityInfo != null && candidate.activityInfo.packageName.startsWith("com.amazon.tv.settings")) {
                    settings = new Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.LEANBACK_LAUNCHER");
                    settings.setComponent(new ComponentName(candidate.activityInfo.packageName, candidate.activityInfo.name));
                    settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ri = candidate;
                    break;
                }
            }
            if (ri == null) return err("The Fire TV settings were not found");
        }
        // if the settings screen lives inside Amazon's home app, the Home watcher must not bounce it back to Hearth
        if (ri.activityInfo != null && HomeState.AMAZON_HOME.equals(ri.activityInfo.packageName)) HomeState.standAside(30000);
        activity.startFromBridge(settings);
        return ok(true);
    }

    // ------------------------------------------------------------------ uninstall

    private String uninstall(String id) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(id, 0);
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 || id.equals(activity.getPackageName())) {
                return err("This app came with the Fire TV, so it can't be removed");
            }
        } catch (PackageManager.NameNotFoundException e) {
            return err("That app is not installed");
        }
        uninstalls.put(id, "pending");
        activity.startUninstall(id);
        return ok(true);
    }

    /** Called by the activity when the system's "uninstall this app?" screen has closed. */
    void uninstallFinished(String id) {
        if (id == null) return;
        uninstalls.put(id, packageExists(id) ? "cancelled" : "removed");
    }

    private String uninstallState(String id) {
        String state = uninstalls.get(id);
        return state == null ? "cancelled" : state;
    }

    // ------------------------------------------------------------------ the Fire TV itself

    private JSONObject info() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("fireOs", fireOsVersion());
        o.put("device", Build.MODEL == null ? "" : Build.MODEL);
        o.put("ip", ipAddress());
        return o;
    }

    private static String fireOsVersion() {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            Method get = props.getMethod("get", String.class);
            String name = (String) get.invoke(null, "ro.build.version.name");       // e.g. "Fire OS 6.7.1.1"
            if (name != null && name.length() > 0) return name.replace("Fire OS", "").trim();
        } catch (Exception ignored) {
            // not readable: show the Android version instead
        }
        return "Android " + Build.VERSION.RELEASE;
    }

    private static String ipAddress() {
        try {
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            while (nets != null && nets.hasMoreElements()) {
                NetworkInterface net = nets.nextElement();
                if (net.isLoopback() || !net.isUp()) continue;
                Enumeration<InetAddress> addresses = net.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress a = addresses.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
            // no address to show
        }
        return "";
    }

    private JSONObject homeStatus() throws JSONException {
        boolean service = serviceEnabled();
        boolean amazonOff = amazonHomeDisabled();
        boolean isDefault = false;
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            isDefault = ri != null && ri.activityInfo != null && activity.getPackageName().equals(ri.activityInfo.packageName);
        } catch (RuntimeException ignored) {
            // unknown: leave false
        }
        JSONObject o = new JSONObject();
        o.put("homeService", service);
        o.put("canEnable", canWriteSecure());
        o.put("boot", HomeState.bootEnabled(activity));
        o.put("amazonHomeDisabled", amazonOff);
        o.put("isDefaultHome", isDefault);
        o.put("takesOverHome", service || amazonOff);
        o.put("storage", activity.checkCallingOrSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED);
        o.put("fireOs", fireOsVersion());
        o.put("device", Build.MODEL == null ? "" : Build.MODEL);
        o.put("ip", ipAddress());
        o.put("grantCommand", ADB_GRANT);
        return o;
    }

    /** What the Home helper is doing and what it has seen: the answer is shown under Settings → Options → Test the Home button. */
    private JSONObject homeTest() throws JSONException {
        boolean enabled = serviceEnabled();
        boolean running = HomeState.serviceRunning();
        boolean amazonOff = amazonHomeDisabled();
        List<String> recent = HomeState.recentWindows();
        int times = HomeState.redirectCount();

        List<String> lines = new ArrayList<String>();
        lines.add("Helper switched on in the Fire TV: " + (enabled ? "yes." : "no."));
        lines.add("Helper running now: " + (running ? "yes." : "no."));
        lines.add("Home screens it watches for: " + joined(HomeState.homePackages(activity)) + ".");
        lines.add(recent.isEmpty() ? "Screens seen so far: none yet." : "Screens seen, newest first: " + joined(recent) + ".");
        if (amazonOff) lines.add("Amazon's home app is switched off, so Home opens Hearth directly.");
        if (times > 0) {
            lines.add("Hearth opened over a home screen " + times + (times == 1 ? " time" : " times") + ", the last " + HomeState.secondsSinceRedirect() + " s ago. RESULT: working.");
        } else if (amazonOff) {
            lines.add("RESULT: nothing to do, Amazon's home is off.");
        } else if (!enabled) {
            lines.add("RESULT: not on. Turn on Open Hearth when Home is pressed.");
        } else if (!running) {
            lines.add("RESULT: switched on, but not running yet. Restart the Fire TV, or switch it off and on again.");
        } else {
            lines.add("RESULT: not proven yet. Open an app, press Home on the remote, then come back here and press OK again.");
        }

        JSONArray out = new JSONArray();
        for (String line : lines) out.put(line);
        JSONObject o = new JSONObject();
        o.put("lines", out);
        return o;
    }

    private static String joined(Collection<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(item);
        }
        return sb.length() == 0 ? "(none)" : sb.toString();
    }

    private boolean amazonHomeDisabled() {
        try {
            int state = pm.getApplicationEnabledSetting(HomeState.AMAZON_HOME);
            return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
        } catch (IllegalArgumentException e) {
            return true;        // not installed at all
        }
    }

    // ------------------------------------------------------------------ the "open Hearth when Home is pressed" service

    private boolean canWriteSecure() {
        return activity.checkCallingOrSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    private List<String> enabledServices() {
        List<String> list = new ArrayList<String>();
        String raw = Settings.Secure.getString(activity.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (raw == null) return list;
        for (String part : raw.split(":")) {
            if (part.length() > 0) list.add(part);
        }
        return list;
    }

    private boolean isOurs(String entry) {
        ComponentName cn = ComponentName.unflattenFromString(entry);
        return cn != null && cn.equals(HomeState.service(activity));
    }

    private boolean serviceEnabled() {
        ContentResolver cr = activity.getContentResolver();
        if (Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) return false;
        for (String entry : enabledServices()) {
            if (isOurs(entry)) return true;
        }
        return false;
    }

    private String setHomeTakeover(boolean on) throws JSONException {
        JSONObject result = new JSONObject();
        if (!canWriteSecure()) {
            result.put("enabled", serviceEnabled());
            result.put("needsSetup", true);
            return ok(result);
        }
        try {
            ContentResolver cr = activity.getContentResolver();
            List<String> keep = new ArrayList<String>();
            for (String entry : enabledServices()) {
                if (!isOurs(entry)) keep.add(entry);
            }
            if (on) keep.add(HomeState.service(activity).flattenToString());
            StringBuilder joined = new StringBuilder();
            for (String entry : keep) {
                if (joined.length() > 0) joined.append(':');
                joined.append(entry);
            }
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, joined.toString());
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, keep.isEmpty() ? 0 : 1);
            result.put("enabled", on);
            result.put("needsSetup", false);
            return ok(result);
        } catch (SecurityException e) {
            result.put("enabled", serviceEnabled());
            result.put("needsSetup", true);
            return ok(result);
        }
    }

    private String openAccessibility() {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (pm.resolveActivity(intent, 0) == null) {
            return err("Open the Fire TV's Settings, then Accessibility, and turn Hearth on");
        }
        HomeState.standAside(60000);
        activity.startFromBridge(intent);
        return ok(true);
    }

    // ------------------------------------------------------------------ storage (wallpaper pictures)

    private JSONArray roots() throws JSONException {
        JSONArray out = new JSONArray();
        List<File> usb = new ArrayList<File>();
        File[] volumes = new File("/storage").listFiles();
        if (volumes != null) {
            for (File v : volumes) {
                String name = v.getName();
                if (name.equals("emulated") || name.equals("self") || !v.isDirectory() || !v.canRead()) continue;
                usb.add(v);
            }
        }
        Collections.sort(usb);
        for (int i = 0; i < usb.size(); i++) {
            JSONObject r = new JSONObject();
            r.put("label", usb.size() == 1 ? "USB drive" : "USB drive " + (i + 1));
            r.put("path", usb.get(i).getAbsolutePath());
            out.put(r);
        }
        JSONObject internal = new JSONObject();
        internal.put("label", "Internal storage");
        internal.put("path", Environment.getExternalStorageDirectory().getAbsolutePath());
        out.put(internal);
        return out;
    }

    private String listDir(String path) throws Exception {
        File dir = WebHost.safeStorageFile(path);
        if (dir == null) return err("That folder can't be used");
        File[] files = dir.listFiles();
        if (files == null) return err("Can't read this folder. Is the storage permission allowed for Hearth?");
        JSONArray out = new JSONArray();
        int count = 0;
        for (File f : files) {
            if (count++ >= 5000) break;
            JSONObject e = new JSONObject();
            e.put("name", f.getName());
            e.put("isDir", f.isDirectory());
            out.put(e);
        }
        return ok(out);
    }

    private String fileInfo(String path) throws Exception {
        File f = WebHost.safeStorageFile(path);
        if (f == null || !f.isFile() || !f.canRead()) return err("Can't read that picture");
        JSONObject o = new JSONObject();
        o.put("size", f.length());
        return ok(o);
    }

}
