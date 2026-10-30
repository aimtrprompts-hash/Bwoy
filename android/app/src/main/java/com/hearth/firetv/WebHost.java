package com.hearth.firetv;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Serves everything the page loads from https://hearth.local/ without touching the network:
 *   /...            Hearth's own files (the web build, kept in the app's assets/web)
 *   /__icon/<pkg>   an installed app's icon, as a PNG
 *   /__file?p=...   a picture from the Fire TV's storage or a USB drive (wallpaper)
 * Using a real https address (instead of file://) gives the page a normal origin, so saved settings and pictures just work.
 */
final class WebHost {
    static final String HOST = "hearth.local";
    private static final int ICON_PIXELS = 256;
    private static final Pattern PACKAGE = Pattern.compile("^[A-Za-z0-9._]+$");
    private static final Pattern DIGITS = Pattern.compile("^[0-9]{1,20}$");
    private static final Map<String, String> MIME = new HashMap<String, String>();

    static {
        MIME.put("html", "text/html");
        MIME.put("js", "application/javascript");
        MIME.put("css", "text/css");
        MIME.put("json", "application/json");
        MIME.put("txt", "text/plain");
        MIME.put("svg", "image/svg+xml");
        MIME.put("png", "image/png");
        MIME.put("jpg", "image/jpeg");
        MIME.put("jpeg", "image/jpeg");
        MIME.put("webp", "image/webp");
        MIME.put("bmp", "image/bmp");
        MIME.put("woff", "font/woff");
        MIME.put("woff2", "font/woff2");
    }

    private final Context context;
    private final PackageManager pm;

    WebHost(Context context) {
        this.context = context;
        this.pm = context.getPackageManager();
    }

    /** The answer for a request to the page's own address, or null for anything else (the network handles that). */
    WebResourceResponse handle(Uri uri) {
        if (uri == null || !HOST.equals(uri.getHost())) return null;
        String path = uri.getPath() == null ? "" : uri.getPath();
        try {
            if (path.startsWith("/__icon/")) return icon(path.substring("/__icon/".length()), uri.getQueryParameter("v"));
            if (path.equals("/__file")) return file(uri.getQueryParameter("p"));
            return asset(path);
        } catch (IOException e) {
            return notFound();
        } catch (RuntimeException e) {
            return notFound();
        }
    }

    // ------------------------------------------------------------------ pieces

    private WebResourceResponse asset(String path) throws IOException {
        String name = path.startsWith("/") ? path.substring(1) : path;
        if (name.length() == 0) name = "index.html";
        if (name.contains("..") || name.contains("\\")) return notFound();
        InputStream in = context.getAssets().open("web/" + name);
        return response(mimeFor(name), in);
    }

    private WebResourceResponse icon(String pkg, String version) throws IOException {
        if (!PACKAGE.matcher(pkg).matches()) return notFound();
        String v = version != null && DIGITS.matcher(version).matches() ? version : "0";
        File dir = new File(context.getCacheDir(), "icons");
        if (!dir.isDirectory() && !dir.mkdirs()) return notFound();
        File cached = new File(dir, pkg + "_" + v + ".png");
        if (!cached.isFile()) {
            byte[] png = renderIcon(pkg);
            if (png == null) return notFound();
            // one file per app and version: older versions of the same app's icon are dropped
            File[] old = dir.listFiles();
            if (old != null) {
                for (File f : old) {
                    if (f.getName().startsWith(pkg + "_")) f.delete();
                }
            }
            FileOutputStream out = new FileOutputStream(cached);
            try {
                out.write(png);
            } finally {
                out.close();
            }
        }
        return response("image/png", new FileInputStream(cached));
    }

    private byte[] renderIcon(String pkg) {
        try {
            Drawable d = pm.getApplicationIcon(pkg);
            Bitmap bitmap = Bitmap.createBitmap(ICON_PIXELS, ICON_PIXELS, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            int w = d.getIntrinsicWidth();
            int h = d.getIntrinsicHeight();
            if (w <= 0 || h <= 0) {
                w = ICON_PIXELS;
                h = ICON_PIXELS;
            }
            float scale = Math.min((float) ICON_PIXELS / w, (float) ICON_PIXELS / h);
            int dw = Math.round(w * scale);
            int dh = Math.round(h * scale);
            int left = (ICON_PIXELS - dw) / 2;
            int top = (ICON_PIXELS - dh) / 2;
            d.setBounds(left, top, left + dw, top + dh);
            d.draw(canvas);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes);
            bitmap.recycle();
            return bytes.toByteArray();
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private WebResourceResponse file(String path) throws IOException {
        File f = safeStorageFile(path);
        if (f == null || !f.isFile() || !f.canRead()) return notFound();
        return response(mimeFor(f.getName()), new FileInputStream(f));
    }

    // ------------------------------------------------------------------ helpers

    /** A file or folder on shared storage or a USB drive, or null for any other place. */
    static File safeStorageFile(String path) {
        if (path == null || path.length() == 0 || path.charAt(0) != '/') return null;
        try {
            File f = new File(path).getCanonicalFile();
            String p = f.getPath();
            if (p.equals("/storage") || p.startsWith("/storage/") || p.startsWith("/mnt/media_rw/") || p.startsWith("/mnt/runtime/") || p.startsWith("/mnt/sdcard")) return f;
        } catch (IOException ignored) {
            // not a usable path
        }
        return null;
    }

    private static String mimeFor(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String mime = MIME.get(ext);
        return mime == null ? "application/octet-stream" : mime;
    }

    private static WebResourceResponse response(String mime, InputStream data) {
        boolean text = mime.startsWith("text/") || mime.equals("application/javascript") || mime.equals("application/json");
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("Cache-Control", "no-cache");
        return new WebResourceResponse(mime, text ? "UTF-8" : null, 200, "OK", headers, data);
    }

    private static WebResourceResponse notFound() {
        Map<String, String> headers = new HashMap<String, String>();
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", headers, new ByteArrayInputStream(new byte[0]));
    }
}
