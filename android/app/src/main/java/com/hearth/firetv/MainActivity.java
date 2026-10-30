package com.hearth.firetv;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

/**
 * Hosts Hearth's web page in a full-screen WebView. The page itself is the same one the LG version runs;
 * Bridge gives it what webOS gave the LG version (app list, launching, pictures), and WebHost serves its files.
 */
public class MainActivity extends Activity {
    private static final String TAG = "Hearth";
    private static final int REQ_STORAGE = 1;
    private static final int REQ_UNINSTALL = 2;

    private WebView web;
    private Bridge bridge;
    private String pendingUninstall;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setBackgroundColor(Color.BLACK);

        try {
            web = new WebView(this);
        } catch (RuntimeException e) {
            // a Fire TV without a web view component cannot run Hearth
            TextView message = new TextView(this);
            message.setTextColor(Color.WHITE);
            message.setTextSize(28);
            message.setPadding(120, 120, 120, 120);
            message.setText("Hearth needs the Fire TV's web view (Android System WebView), and it is not available.\n\n" + e.getMessage());
            setContentView(message);
            return;
        }

        web.setBackgroundColor(Color.BLACK);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        // The page is laid out for a 1920 px wide screen (its viewport tag says so); the web view then scales it to fit.
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        bridge = new Bridge(this);
        final WebHost host = new WebHost(this);
        web.addJavascriptInterface(bridge, "HearthAndroid");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return host.handle(request.getUrl());
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.d(TAG, m.message() + " (" + m.sourceId() + ":" + m.lineNumber() + ")");
                return true;
            }
        });

        setContentView(web);
        web.requestFocus();
        web.loadUrl("https://" + WebHost.HOST + "/index.html");

        askForStorageOnce();
    }

    // ------------------------------------------------------------------ keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // A web view never shows Back to the page, and Back must never close the home screen. The page handles it
        // (as the key LG's remotes send, code 461): Settings goes back one screen, the home row jumps to the first tile.
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (web != null && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                web.evaluateJavascript("window.__hearthKey&&window.__hearthKey('keydown',461,false)", null);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        // consumed in dispatchKeyEvent; nothing to do here
    }

    // ------------------------------------------------------------------ coming to the front

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Home pressed (or Hearth opened again) while it was already running: the page goes back to its first screen
        if (web != null) web.evaluateJavascript("document.dispatchEvent(new Event('webOSRelaunch'))", null);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (web != null) web.onResume();
    }

    @Override
    protected void onStop() {
        if (web != null) web.onPause();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.removeJavascriptInterface("HearthAndroid");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ used by Bridge

    /** Starts another screen on behalf of the page (always on the main thread). */
    void startFromBridge(final Intent intent) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    startActivity(intent);
                } catch (RuntimeException e) {
                    Log.w(TAG, "Could not start " + intent, e);
                }
            }
        });
    }

    void startUninstall(final String id) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                pendingUninstall = id;
                try {
                    Intent intent = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + id));
                    intent.putExtra(Intent.EXTRA_RETURN_RESULT, true);
                    startActivityForResult(intent, REQ_UNINSTALL);
                } catch (RuntimeException e) {
                    Log.w(TAG, "Could not ask to uninstall " + id, e);
                    bridge.uninstallFinished(id);
                }
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_UNINSTALL && bridge != null) {
            bridge.uninstallFinished(pendingUninstall);
            pendingUninstall = null;
        }
    }

    // ------------------------------------------------------------------ storage permission (wallpaper pictures)

    private void askForStorageOnce() {
        if (Build.VERSION.SDK_INT < 23) return;
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) return;
        if (HomeState.askedStorage(this)) return;
        HomeState.setAskedStorage(this);
        requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
    }
}
