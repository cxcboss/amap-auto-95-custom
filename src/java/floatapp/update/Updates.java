package floatapp.update;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** All state is owned by the main looper; HTTP, hashing and archive parsing use one worker. */
public final class Updates {
    public enum State { IDLE, CHECKING, LATEST, AVAILABLE, DOWNLOADING, VERIFYING, READY }
    public static final class Release {
        public final int code, minSdk;
        public final long size;
        public final String name, asset, localName, url, sha256, notes, json;
        Release(JSONObject data) throws JSONException, IOException {
            code = data.getInt("versionCode"); name = data.getString("versionName");
            minSdk = data.getInt("minSdk"); size = data.getLong("size");
            asset = data.getString("assetName"); url = data.getString("downloadUrl");
            localName = name + "高德地图.apk";
            sha256 = data.getString("sha256"); notes = data.getString("notes"); json = data.toString();
            if (data.getInt("schemaVersion") != 1 || !BuildInfo.PACKAGE_NAME.equals(data.getString("packageName"))
                    || !name.matches("[1-9][0-9]*\\.[0-9]\\.9\\.5")
                    || !(asset.equals("amap-auto-" + name + ".apk") || asset.equals(localName))
                    || code <= 0 || code >= 2100000000
                    || minSdk < 21 || size <= 0 || !sha256.matches("[0-9a-f]{64}"))
                throw new IOException("版本信息不匹配");
            releaseUrl(url, "v" + name, asset);
        }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "AmapUpdate"); thread.setPriority(Thread.MIN_PRIORITY); return thread;
    });
    private static Context app;
    private static SharedPreferences prefs;
    private static Runnable changed;
    public static State state = State.IDLE;
    public static Release release;
    public static String status = "尚未检查", error = "";
    public static int progress;
    private static File downloaded;
    private static volatile boolean cancelled;

    public static void initialize(Context context, Runnable observer) {
        changed = observer;
        if (app != null) return;
        app = context.getApplicationContext();
        prefs = app.getSharedPreferences("custom_updates", Context.MODE_PRIVATE);
        // An installed version, rather than an installer intent, confirms a completed update.
        int downloadedCode = prefs.getInt("downloadedCode", 0);
        if (downloadedCode > 0 && downloadedCode <= BuildInfo.VERSION_CODE)
            prefs.edit().remove("downloadedCode").remove("downloadedRelease").apply();
        else if (downloadedCode > BuildInfo.VERSION_CODE) {
            state = State.VERIFYING; status = "正在校验安装包";
            IO.execute(() -> {
                try {
                    Release cached = new Release(new JSONObject(prefs.getString("downloadedRelease", "")));
                    File file = new File(directory(), cached.localName);
                    verify(file, cached);
                    MAIN.post(() -> { release = cached; downloaded = file; state = State.READY; status = "已下载"; notifyChange(); });
                } catch (IOException | JSONException invalid) {
                    prefs.edit().remove("downloadedCode").remove("downloadedRelease").apply();
                    MAIN.post(() -> { state = State.IDLE; status = "尚未检查"; notifyChange(); });
                }
            });
        }
        MAIN.postDelayed(() -> {
            if (autoCheck() && System.currentTimeMillis() - prefs.getLong("lastCheck", 0) >= 86400000L) check();
        }, 45000);
    }

    public static boolean busy() { return state == State.CHECKING || state == State.DOWNLOADING || state == State.VERIFYING; }
    public static boolean autoCheck() { return prefs.getBoolean("autoCheck", true); }
    public static void toggleAutoCheck() { prefs.edit().putBoolean("autoCheck", !autoCheck()).apply(); notifyChange(); }
    public static File directory() { return new File(Environment.getExternalStorageDirectory(), "高德更新"); }
    public static String path() { return downloaded == null ? "" : downloaded.getAbsolutePath(); }
    private static void notifyChange() { changed.run(); }

    public static void check() {
        if (busy()) return;
        final State previous = state;
        final Release previousRelease = release;
        state = State.CHECKING; status = "正在检查"; error = ""; notifyChange();
        prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply();
        IO.execute(() -> {
            try {
                HttpURLConnection request = connect("https://api.github.com/repos/" + BuildInfo.REPOSITORY + "/releases/latest");
                JSONObject github;
                try {
                    int response = request.getResponseCode();
                    if (response == 404) {
                        MAIN.post(() -> { state = previous; status = "暂无发布版本"; notifyChange(); }); return;
                    }
                    requireSuccess(request);
                    github = readJson(request);
                } finally { request.disconnect(); }
                if (github.getBoolean("draft") || github.getBoolean("prerelease")) throw new IOException("未找到正式版本");
                JSONArray assets = github.getJSONArray("assets");
                JSONObject metadata = null;
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    if (asset.getString("name").equals("update.json")) metadata = asset;
                }
                if (metadata == null) throw new IOException("发布文件不完整");
                String metadataUrl = metadata.getString("browser_download_url");
                releaseUrl(metadataUrl, github.getString("tag_name"), "update.json");
                HttpURLConnection info = connect(metadataUrl);
                Release found;
                try { requireSuccess(info); found = new Release(readJson(info)); }
                finally { info.disconnect(); }
                JSONObject apk = null;
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    if (asset.getString("name").equals(found.asset)) apk = asset;
                }
                if (apk == null) throw new IOException("发布文件不完整");
                if (!github.getString("tag_name").equals("v" + found.name)
                        || !apk.getString("name").equals(found.asset) || apk.getLong("size") != found.size
                        || !apk.getString("browser_download_url").equals(found.url)) throw new IOException("发布文件不匹配");
                if (found.minSdk > Build.VERSION.SDK_INT) throw new IOException("新版本不支持当前安卓系统");
                MAIN.post(() -> {
                    release = found;
                    downloaded = new File(directory(), found.localName);
                    state = previous == State.READY && found.code == previousRelease.code && found.sha256.equals(previousRelease.sha256)
                            ? State.READY : found.code > BuildInfo.VERSION_CODE ? State.AVAILABLE : State.LATEST;
                    status = state == State.READY ? "已下载" : state == State.AVAILABLE ? "发现新版本" : "已是最新版本";
                    notifyChange();
                });
            } catch (IOException | JSONException failure) {
                MAIN.post(() -> { state = previous;
                    status = "检查失败"; error = message(failure); notifyChange(); });
            }
        });
    }

    public static void download(Activity host) {
        if (busy() || release == null || release.code <= BuildInfo.VERSION_CODE) return;
        if (Build.VERSION.SDK_INT >= 23 && host.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            host.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 9510);
            error = "允许存储权限后，请重试下载"; notifyChange(); return;
        }
        final Release target = release;
        final WeakReference<Activity> installer = new WeakReference<>(host);
        state = State.DOWNLOADING; status = "正在下载"; error = ""; progress = 0; cancelled = false; notifyChange();
        IO.execute(() -> {
            File folder = directory(), output = new File(folder, target.localName), partial = new File(folder, target.localName + ".part");
            try {
                if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())
                        || (!folder.isDirectory() && !folder.mkdirs())) throw new IOException("无法写入高德更新目录");
                if (output.isFile()) {
                    MAIN.post(() -> { state = State.VERIFYING; status = "正在校验安装包"; notifyChange(); });
                    try { verify(output, target); }
                    catch (IOException invalid) {
                        if (!output.delete()) throw new IOException("无法替换原有安装包");
                    }
                }
                if (!output.isFile()) {
                    MAIN.post(() -> { state = State.DOWNLOADING; status = "正在下载"; notifyChange(); });
                    HttpURLConnection request = connect(target.url);
                    try {
                        requireSuccess(request);
                        try (InputStream input = request.getInputStream(); FileOutputStream file = new FileOutputStream(partial)) {
                            byte[] bytes = new byte[65536]; long total = 0, last = 0; int count;
                            while ((count = input.read(bytes)) != -1) {
                                if (cancelled) throw new IOException("下载已取消");
                                total += count;
                                if (total > target.size) throw new IOException("安装包大小不匹配");
                                file.write(bytes, 0, count);
                                long now = android.os.SystemClock.elapsedRealtime();
                                if (now - last >= 700) {
                                    final int percent = (int) (total * 100 / target.size);
                                    MAIN.post(() -> { progress = percent; notifyChange(); }); last = now;
                                }
                            }
                            file.getFD().sync();
                        }
                    } finally { request.disconnect(); }
                    MAIN.post(() -> { state = State.VERIFYING; status = "正在校验安装包"; notifyChange(); });
                    verify(partial, target);
                    if (!partial.renameTo(output)) throw new IOException("无法保存安装包");
                }
                if (cancelled) throw new IOException("下载已取消");
                MAIN.post(() -> {
                    downloaded = output; state = State.READY; progress = 100; status = "已下载";
                    prefs.edit().putInt("downloadedCode", target.code).putString("downloadedRelease", target.json).apply();
                    notifyChange();
                    Activity active = installer.get();
                    if (active != null && !active.isFinishing() && active.hasWindowFocus()) launchInstaller(active);
                });
            } catch (IOException failure) {
                partial.delete();
                MAIN.post(() -> { state = State.AVAILABLE; status = cancelled ? "下载已取消" : "下载失败";
                    error = cancelled ? "" : message(failure); notifyChange(); });
            }
        });
    }

    public static void cancel() { cancelled = true; status = "正在取消"; notifyChange(); }

    public static void install(Activity host) {
        if (busy() || state != State.READY) return;
        final Release target = release; final File file = downloaded;
        final WeakReference<Activity> installer = new WeakReference<>(host);
        state = State.VERIFYING; status = "正在校验安装包"; error = ""; notifyChange();
        // A file in shared storage is external input again after returning from another app.
        IO.execute(() -> {
            try {
                verify(file, target);
                MAIN.post(() -> { state = State.READY; status = "已下载"; notifyChange();
                    Activity active = installer.get();
                    if (active != null && !active.isFinishing()) launchInstaller(active); });
            } catch (IOException invalid) {
                MAIN.post(() -> { state = State.AVAILABLE; status = "安装包校验失败"; error = message(invalid); notifyChange(); });
            }
        });
    }

    private static void launchInstaller(Activity host) {
        if (Build.VERSION.SDK_INT >= 26 && app.getApplicationInfo().targetSdkVersion >= 26
                && !host.getPackageManager().canRequestPackageInstalls()) {
            error = "请允许安装此来源的应用后重试"; notifyChange();
            try { host.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + BuildInfo.PACKAGE_NAME))); }
            catch (ActivityNotFoundException | SecurityException unavailable) { error = "请在文件管理器中打开安装包"; notifyChange(); }
            return;
        }
        try {
            // The base APK obfuscates FileProvider's static helper. Its URI contract is stable.
            Uri uri = new Uri.Builder().scheme("content").authority(BuildInfo.PACKAGE_NAME + ".bootstrap.fileProvider")
                    .appendPath("custom-updates").appendPath(downloaded.getName()).build();
            Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newRawUri("高德更新", uri));
            host.startActivity(intent);
            status = "等待系统安装确认"; error = "";
        } catch (ActivityNotFoundException | SecurityException | IllegalArgumentException unavailable) {
            status = "已下载"; error = "请在文件管理器中打开安装包";
        }
        // OEM installers can launch and still refuse later; always keep the file and retry actions.
        notifyChange();
    }

    public static void copyPath() {
        ((ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("高德更新", path()));
        status = "路径已复制"; notifyChange();
    }

    @SuppressWarnings("deprecation")
    private static void verify(File file, Release target) throws IOException {
        if (file.length() != target.size) throw new IOException("安装包大小不匹配");
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        try (InputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[65536]; int count;
            while ((count = input.read(bytes)) != -1) digest.update(bytes, 0, count);
        }
        StringBuilder hash = new StringBuilder(64);
        for (byte value : digest.digest()) hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        if (!hash.toString().equals(target.sha256)) throw new IOException("安装包校验失败，请重新下载");
        PackageManager manager = app.getPackageManager();
        PackageInfo archive = manager.getPackageArchiveInfo(file.getAbsolutePath(), PackageManager.GET_SIGNATURES);
        // The coexist base substitutes PackageInfo.signatures. Read the installed APK's real signer.
        PackageInfo installed = manager.getPackageArchiveInfo(app.getApplicationInfo().sourceDir, PackageManager.GET_SIGNATURES);
        if (archive == null || installed == null || !BuildInfo.PACKAGE_NAME.equals(archive.packageName)
                || archive.versionCode != target.code || !target.name.equals(archive.versionName)
                || archive.versionCode <= installed.versionCode || archive.signatures == null
                || !Arrays.equals(archive.signatures, installed.signatures))
            throw new IOException("安装包与当前应用不兼容");
    }

    private static HttpURLConnection connect(String address) throws IOException {
        HttpURLConnection request = (HttpURLConnection) new URL(address).openConnection();
        request.setConnectTimeout(15000); request.setReadTimeout(20000);
        request.setRequestProperty("User-Agent", "AmapCustom/" + BuildInfo.VERSION_NAME);
        request.setRequestProperty("Accept", "application/vnd.github+json");
        request.setUseCaches(false);
        return request;
    }
    private static void requireSuccess(HttpURLConnection request) throws IOException {
        int code = request.getResponseCode();
        if (code != 200) throw new IOException(code == 403 || code == 429 ? "请求过于频繁，请稍后重试" : "无法连接更新服务");
    }
    private static JSONObject readJson(HttpURLConnection request) throws IOException, JSONException {
        try (InputStream input = request.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[8192]; int count;
            while ((count = input.read(bytes)) != -1) {
                if (out.size() + count > 262144) throw new IOException("更新信息过大");
                out.write(bytes, 0, count);
            }
            return new JSONObject(out.toString("UTF-8"));
        }
    }
    private static void releaseUrl(String address, String tag, String asset) throws IOException {
        URL url = new URL(address);
        String path = Uri.decode(url.getPath());
        if (!url.getProtocol().equals("https") || !url.getHost().equals("github.com")
                || !path.equals("/" + BuildInfo.REPOSITORY + "/releases/download/" + tag + "/" + asset))
            throw new IOException("更新来源不匹配");
    }
    private static String message(Exception failure) {
        if (failure instanceof JSONException) return "版本信息格式错误";
        String text = failure.getMessage();
        return text != null && text.matches(".*[\\u4e00-\\u9fff].*") ? text : "网络或存储不可用，请稍后重试";
    }
}
