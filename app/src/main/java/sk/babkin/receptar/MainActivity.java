package sk.babkin.receptar;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Thin WebView shell. UI lives in assets/www; recipes and app updates come from the devbox store. */
public class MainActivity extends Activity {
    static final String STORE = "https://devbox.tail20cc8f.ts.net/apps";
    static final String PROJECT = "babkin-receptar";
    static final String RECIPES_URL = STORE + "/f/" + PROJECT + "/recepty.data";

    WebView web;
    Updater updater;
    boolean resumeInstall = false;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        updater = new Updater(this);
        updater.cleanup();
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setTextZoom(100); // our own text-size setting, ignore the system font scale doubling it
        if (Build.VERSION.SDK_INT >= 23) s.setOffscreenPreRaster(true);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) { return !url.startsWith("file:"); }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.setBackgroundColor(0xFFFBF5EA);
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl("file:///android_asset/www/index.html");
    }

    @Override public void onBackPressed() {
        web.evaluateJavascript("window.goBack && window.goBack()", v -> {
            if (!"true".equals(v)) MainActivity.super.onBackPressed();
        });
    }

    @Override protected void onResume() {
        super.onResume();
        if (resumeInstall) { resumeInstall = false; updater.install(); }
    }

    void js(String code) { web.post(() -> web.evaluateJavascript(code, null)); }

    /** Lets the user allow "install unknown apps" once, then continues in onResume. */
    boolean ensureInstallPermission() {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            resumeInstall = true;
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return false;
        }
        return true;
    }

    static String readAll(InputStream in) throws Exception {
        try (InputStream i = in) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384]; int n;
            while ((n = i.read(buf)) > 0) o.write(buf, 0, n);
            return new String(o.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static int recipesVersion(String json) {
        try { JSONObject o = new JSONObject(json); o.getJSONArray("recipes"); return o.optInt("version", 0); }
        catch (Exception e) { return -1; }
    }

    File cachedRecipes() { return new File(getFilesDir(), "recipes.json"); }

    String bestRecipes() {
        String bundled = "";
        try { bundled = readAll(getAssets().open("www/recipes.json")); } catch (Exception ignored) {}
        File f = cachedRecipes();
        if (f.isFile()) {
            try {
                String cached = readAll(new java.io.FileInputStream(f));
                if (recipesVersion(cached) >= recipesVersion(bundled)) return cached;
            } catch (Exception ignored) {}
        }
        return bundled;
    }

    class Bridge {
        @JavascriptInterface public String getRecipes() { return bestRecipes(); }

        @JavascriptInterface public String appVersion() {
            return BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")";
        }

        /** Downloads the recipe feed; if it is valid and newer, saves it and hands it to the UI. */
        @JavascriptInterface public void refreshRecipes() {
            new Thread(() -> {
                try {
                    String json = Updater.httpGet(RECIPES_URL);
                    int v = recipesVersion(json);
                    if (v < 0) return;
                    if (v > recipesVersion(bestRecipes())) {
                        File tmp = new File(getFilesDir(), "recipes.json.tmp");
                        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(json.getBytes(StandardCharsets.UTF_8)); }
                        //noinspection ResultOfMethodCallIgnored
                        tmp.renameTo(cachedRecipes());
                        js("window.onRecipesUpdated(" + JSONObject.quote(json) + ")");
                    }
                } catch (Exception ignored) { /* offline: keep what we have */ }
            }).start();
        }

        @JavascriptInterface public void checkUpdate(boolean manual) { updater.check(manual); }

        @JavascriptInterface public void startUpdate() { runOnUiThread(() -> { if (ensureInstallPermission()) updater.install(); }); }

        @JavascriptInterface public void keepScreenOn(boolean on) {
            runOnUiThread(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface public void vibrate(int ms) {
            try {
                Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (v == null) return;
                if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
                else v.vibrate(ms);
            } catch (Exception ignored) {}
        }
    }
}
