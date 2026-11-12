package app.billet.ledger;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.json.JSONObject;

/**
 * Billet for Android: shows the bundled Billet page (assets/index.html) in a WebView.
 * Data lives in the WebView's local storage on this phone. The app has no internet permission.
 * The page talks to the app through window.BilletAndroid to save files and colour the system bars.
 */
public class MainActivity extends Activity {
    private static final int REQ_OPEN = 1;
    private static final int REQ_SAVE = 2;
    private static final String START_URL = "file:///android_asset/index.html";

    private WebView web;
    private ValueCallback<Uri[]> pendingPick;
    private String pendingText;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);      // localStorage: where Billet keeps its data
        s.setAllowFileAccess(false);       // bundled assets still load; nothing else on disk does
        s.setAllowContentAccess(true);     // lets the page read a file the user picks
        s.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "BilletAndroid");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if ("file".equals(url.getScheme())) return false;
                // Any other link opens outside the app.
                try { startActivity(new Intent(Intent.ACTION_VIEW, url)); } catch (ActivityNotFoundException ignored) { }
                return true;
            }
        });

        // <input type="file"> (CSV import, backup restore) opens Android's file picker.
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (pendingPick != null) pendingPick.onReceiveValue(null);
                pendingPick = callback;
                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("*/*");
                try {
                    startActivityForResult(pick, REQ_OPEN);
                    return true;
                } catch (ActivityNotFoundException e) {
                    pendingPick = null;
                    return false;
                }
            }
        });

        // A long press outside text fields does nothing: no text selection, no drag shadow under the finger.
        // In text fields it still works, so you can paste.
        web.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                WebView.HitTestResult hit = web.getHitTestResult();
                return hit == null || hit.getType() != WebView.HitTestResult.EDIT_TEXT_TYPE;
            }
        });
        web.setHapticFeedbackEnabled(false);

        if (state != null) web.restoreState(state);
        if (web.getUrl() == null) web.loadUrl(START_URL);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    // Tell the page when Billet leaves the screen and comes back, so the app lock can ask again.
    @Override
    protected void onStop() {
        super.onStop();
        if (web != null) web.evaluateJavascript("window.billetPaused && billetPaused()", null);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (web != null) web.evaluateJavascript("window.billetResumed && billetResumed()", null);
    }

    /** Hands the result of the fingerprint or face prompt to the page: ok, pin, cancel, lockout or error. */
    private void authResult(String kind) {
        web.evaluateJavascript("window.billetAuthResult && billetAuthResult(" + JSONObject.quote(kind) + ")", null);
    }

    @Override
    public void onBackPressed() {
        // Back first closes an open sheet or dialog in the page, then moves between Billet's pages, then leaves the app.
        web.evaluateJavascript("window.billetBack ? billetBack() : false", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String handled) {
                if ("true".equals(handled)) return;
                if ("\"exit\"".equals(handled)) { moveTaskToBack(true); return; } // on the lock screen
                if (web.canGoBack()) web.goBack();
                else finish();
            }
        });
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        Uri uri = (result == RESULT_OK && data != null) ? data.getData() : null;
        if (request == REQ_OPEN && pendingPick != null) {
            pendingPick.onReceiveValue(uri != null ? new Uri[]{uri} : null);
            pendingPick = null;
        } else if (request == REQ_SAVE) {
            String text = pendingText;
            pendingText = null;
            if (uri == null || text == null) return; // the user cancelled the save screen
            boolean ok = false;
            try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                if (out != null) {
                    out.write(text.getBytes(StandardCharsets.UTF_8));
                    ok = true;
                }
            } catch (Exception e) {
                ok = false;
            }
            web.evaluateJavascript(ok ? "window.billetFileSaved && billetFileSaved()" : "window.billetFileFailed && billetFileFailed()", null);
        }
    }

    /** Methods the page can call as window.BilletAndroid.*. */
    private class Bridge {
        /** Ask where to save a CSV export or a backup, then write it there. */
        @JavascriptInterface
        public void saveFile(final String name, final String mime, final String text) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    pendingText = text;
                    Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    save.addCategory(Intent.CATEGORY_OPENABLE);
                    save.setType(mime == null || mime.isEmpty() ? "text/plain" : mime);
                    save.putExtra(Intent.EXTRA_TITLE, name);
                    try {
                        startActivityForResult(save, REQ_SAVE);
                    } catch (ActivityNotFoundException e) {
                        pendingText = null;
                        web.evaluateJavascript("window.billetFileFailed && billetFileFailed()", null);
                    }
                }
            });
        }

        /**
         * The phone's wallpaper colours (Android 12 or newer) as three Material tonal palettes,
         * primary, neutral and neutral variant, keyed by tone 0 (black) to 100 (white).
         * Returns "" on older phones, where the page falls back to Material's default colours.
         */
        @JavascriptInterface
        public String dynamicColors() {
            if (Build.VERSION.SDK_INT < 31) return "";
            String[] groups = {"accent1", "neutral1", "neutral2"};
            String[] keys = {"p", "n", "nv"};
            int[] shades = {0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000};
            StringBuilder json = new StringBuilder("{");
            try {
                for (int g = 0; g < groups.length; g++) {
                    json.append(g == 0 ? "" : ",").append('"').append(keys[g]).append("\":{");
                    for (int i = 0; i < shades.length; i++) {
                        int id = getResources().getIdentifier("system_" + groups[g] + "_" + shades[i], "color", "android");
                        if (id == 0) return "";
                        int c = getColor(id);
                        // Android's shade 0..1000 is Material's tone 100..0.
                        json.append(i == 0 ? "" : ",").append('"').append(100 - shades[i] / 10).append("\":\"")
                            .append(String.format(Locale.ROOT, "#%06x", c & 0xFFFFFF)).append('"');
                    }
                    json.append('}');
                }
            } catch (RuntimeException e) {
                return "";
            }
            return json.append('}').toString();
        }

        /**
         * Can this phone unlock apps with a fingerprint or face? Uses Android's own biometrics
         * (whatever the phone has set up: fingerprint, face unlock, or both).
         * Returns ok, not-enrolled, none (no such hardware), unsupported (Android 9 or older) or unavailable.
         */
        @JavascriptInterface
        public String biometricStatus() {
            if (Build.VERSION.SDK_INT < 29) return "unsupported";
            BiometricManager bm = getSystemService(BiometricManager.class);
            if (bm == null) return "unsupported";
            int r = Build.VERSION.SDK_INT >= 30
                ? bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                : bm.canAuthenticate();
            if (r == BiometricManager.BIOMETRIC_SUCCESS) return "ok";
            if (r == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) return "not-enrolled";
            if (r == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE) return "none";
            return "unavailable";
        }

        /** Show the system's fingerprint or face prompt. The answer comes back through billetAuthResult. */
        @JavascriptInterface
        public void authenticate(final String title, final String subtitle, final String fallback) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (Build.VERSION.SDK_INT < 29) { authResult("error"); return; }
                    try {
                        BiometricPrompt.Builder b = new BiometricPrompt.Builder(MainActivity.this).setTitle(title);
                        if (subtitle != null && !subtitle.isEmpty()) b.setSubtitle(subtitle);
                        b.setConfirmationRequired(false); // face unlock opens straight away, no extra tap
                        if (Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK);
                        b.setNegativeButton(fallback, getMainExecutor(), new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                authResult("pin");
                            }
                        });
                        b.build().authenticate(new CancellationSignal(), getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                            @Override
                            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                                authResult("ok");
                            }

                            @Override
                            public void onAuthenticationError(int code, CharSequence message) {
                                if (code == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED || code == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                                        || code == BiometricPrompt.BIOMETRIC_ERROR_TIMEOUT) authResult("cancel");
                                else if (code == BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT || code == BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT) authResult("lockout");
                                else authResult("error");
                            }
                            // A finger or face that didn't match: the system prompt says so and keeps waiting.
                        });
                    } catch (RuntimeException e) {
                        authResult("error");
                    }
                }
            });
        }

        /** While the app lock is on, show a blank card for Billet in the recent-apps screen (Android 13+). */
        @JavascriptInterface
        public void setRecentsHidden(final boolean hidden) {
            if (Build.VERSION.SDK_INT < 33) return;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    setRecentsScreenshotEnabled(!hidden);
                }
            });
        }

        /** Match the status bar to Billet's header and the navigation bar to its page colour. */
        @JavascriptInterface
        public void setBarColors(final String status, final String nav, final boolean lightNav) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Window w = getWindow();
                        int statusColor = Color.parseColor(status), navColor = Color.parseColor(nav);
                        w.setStatusBarColor(statusColor);
                        w.setNavigationBarColor(navColor);
                        View decor = w.getDecorView();
                        // Dark icons on a light bar, light icons on a dark one (Material You's header is light in light mode).
                        int flags = decor.getSystemUiVisibility();
                        flags = Color.luminance(statusColor) > 0.5f ? flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : flags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                        flags = Color.luminance(navColor) > 0.5f ? flags | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : flags & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                        decor.setSystemUiVisibility(flags);
                    } catch (IllegalArgumentException ignored) {
                        // a colour the platform can't parse: keep the current bars
                    }
                }
            });
        }
    }
}
