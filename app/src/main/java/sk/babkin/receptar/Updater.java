package sk.babkin.receptar;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;

/**
 * Self-update from the devbox public store (GET /apps/api/apps -> {"total", "projects":[{name, meta, files}]}).
 * meta.versionCode is compared with ours; the newest *.apk in files is downloaded, its sha256 checked against
 * meta.sha256[path] when present, its package + versionCode checked, then installed via PackageInstaller.
 */
class Updater {
    final MainActivity a;
    volatile String apkPath;     // store path of the newer APK, set by check()
    volatile String apkSha;
    volatile int remoteCode;
    volatile boolean busy;

    Updater(MainActivity a) { this.a = a; }

    File dir() { return new File(a.getCacheDir(), "update"); }

    /** Remove an APK left from an earlier (finished or failed) update. */
    void cleanup() {
        File[] fs = dir().listFiles();
        if (fs != null) for (File f : fs) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Cache-Control", "no-cache");
        c.setRequestProperty("User-Agent", "BabkinReceptar/" + BuildConfig.VERSION_NAME);
        return c;
    }

    static String httpGet(String url) throws Exception {
        HttpURLConnection c = open(url);
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            return MainActivity.readAll(c.getInputStream());
        } finally { c.disconnect(); }
    }

    /** Parses the store feed. Package-private and static so it can be unit tested with the live payload. */
    static JSONObject findProject(String feed, String name) throws Exception {
        JSONObject root = new JSONObject(feed);
        JSONArray ps = root.optJSONArray("projects");
        if (ps == null) ps = root.optJSONArray("apps");
        if (ps == null) return null;
        for (int i = 0; i < ps.length(); i++) {
            JSONObject p = ps.optJSONObject(i);
            if (p != null && name.equals(p.optString("name"))) return p;
        }
        return null;
    }

    void check(boolean manual) {
        new Thread(() -> {
            try {
                JSONObject p = findProject(httpGet(MainActivity.STORE + "/api/apps"), MainActivity.PROJECT);
                if (p == null) { if (manual) a.js("window.onNoUpdate()"); return; }
                JSONObject meta = p.optJSONObject("meta");
                int code = meta == null ? 0 : meta.optInt("versionCode", 0);
                String apk = null;
                JSONArray files = p.optJSONArray("files");
                long best = -1;
                if (files != null) for (int i = 0; i < files.length(); i++) {
                    JSONObject f = files.getJSONObject(i);
                    String path = f.optString("path");
                    if (path.toLowerCase().endsWith(".apk") && f.optLong("mtime") > best) { best = f.optLong("mtime"); apk = path; }
                }
                if (apk == null || code <= BuildConfig.VERSION_CODE) { if (manual) a.js("window.onNoUpdate()"); return; }
                JSONObject sums = meta.optJSONObject("sha256");
                apkSha = sums == null ? null : sums.optString(apk, null);
                apkPath = apk;
                remoteCode = code;
                String vn = meta.optString("version", "");
                String wn = meta.optString("whatsNew", "");
                a.js("window.onUpdateAvailable(" + JSONObject.quote(vn) + "," + JSONObject.quote(wn) + ")");
            } catch (Exception e) {
                if (manual) a.js("window.onUpdateStatus('Nepodarilo sa spojiť. Skúste neskôr.')");
            }
        }).start();
    }

    void status(String s) { a.js("window.onUpdateStatus(" + JSONObject.quote(s) + ")"); }

    void install() {
        if (apkPath == null || busy) return;
        busy = true;
        new Thread(() -> {
            try {
                status("Sťahujem novú verziu…");
                File d = dir();
                //noinspection ResultOfMethodCallIgnored
                d.mkdirs();
                File out = new File(d, "update.apk");
                StringBuilder enc = new StringBuilder();
                for (String part : apkPath.split("/")) {
                    if (enc.length() > 0) enc.append('/');
                    enc.append(URLEncoder.encode(part, "UTF-8").replace("+", "%20"));
                }
                HttpURLConnection c = open(MainActivity.STORE + "/f/" + MainActivity.PROJECT + "/" + enc);
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(out)) {
                    if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                    byte[] buf = new byte[65536]; int n;
                    while ((n = in.read(buf)) > 0) { o.write(buf, 0, n); md.update(buf, 0, n); }
                } finally { c.disconnect(); }
                if (apkSha != null && !apkSha.isEmpty()) {
                    StringBuilder hex = new StringBuilder();
                    for (byte b : md.digest()) hex.append(String.format("%02x", b));
                    if (!hex.toString().equalsIgnoreCase(apkSha)) throw new Exception("sha256");
                }
                PackageInfo pi = a.getPackageManager().getPackageArchiveInfo(out.getPath(), 0);
                long code = pi == null ? -1 : (Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode);
                if (pi == null || !a.getPackageName().equals(pi.packageName) || code <= BuildConfig.VERSION_CODE)
                    throw new Exception("bad apk");

                status("Inštalujem…");
                PackageInstaller pkg = a.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                sp.setAppPackageName(a.getPackageName());
                int id = pkg.createSession(sp);
                try (PackageInstaller.Session s = pkg.openSession(id)) {
                    try (InputStream in = new FileInputStream(out); OutputStream o = s.openWrite("app.apk", 0, out.length())) {
                        byte[] buf = new byte[65536]; int n;
                        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                        s.fsync(o);
                    }
                    Intent i = new Intent(a, InstallReceiver.class);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                    PendingIntent pi2 = PendingIntent.getBroadcast(a, id, i, flags);
                    s.commit(pi2.getIntentSender());
                }
            } catch (Exception e) {
                status("Aktualizácia sa nepodarila. Skúste neskôr.");
            } finally { busy = false; }
        }).start();
    }
}
