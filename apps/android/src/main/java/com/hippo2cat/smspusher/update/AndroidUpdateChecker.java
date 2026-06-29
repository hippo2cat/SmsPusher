package com.hippo2cat.smspusher.update;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import com.hippo2cat.smspusher.BuildConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AndroidUpdateChecker {
    static final String UPDATE_MANIFEST_URL = "https://hippo2cat.github.io/SmsPusher/updates/stable/latest.json";

    private static final Logger LOGGER = LoggerFactory.getLogger(AndroidUpdateChecker.class);
    private static final String PREFS = "smspusher_android_updates";
    private static final String KEY_LAST_PROMPTED_VERSION = "last_prompted_version";
    private static final String KEY_LAST_PERMISSION_PROMPT_VERSION = "last_permission_prompt_version";
    private static final String APK_MIME_TYPE = "application/vnd.android.package-archive";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    private AndroidUpdateChecker() {
    }

    public static void start(Context context) {
        Context appContext = context.getApplicationContext();
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(() -> checkForUpdate(appContext), "smspusher-android-update-checker");
        thread.setDaemon(true);
        thread.start();
    }

    public static ReleaseInfo parseRelease(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONArray assetArray = root.optJSONArray("assets");
        List<ReleaseAsset> assets = new ArrayList<>();
        if (assetArray != null) {
            for (int i = 0; i < assetArray.length(); i++) {
                JSONObject asset = assetArray.optJSONObject(i);
                if (asset == null) {
                    continue;
                }
                assets.add(new ReleaseAsset(
                    asset.optString("name", ""),
                    asset.optString("browser_download_url", "")
                ));
            }
        }
        return new ReleaseInfo(
            root.optString("tag_name", ""),
            root.optBoolean("draft", false),
            root.optBoolean("prerelease", false),
            assets
        );
    }

    public static UpdateManifest parseManifest(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONObject platforms = root.optJSONObject("platforms");
        JSONObject android = platforms == null ? null : platforms.optJSONObject("android");
        return new UpdateManifest(
            root.optString("version", ""),
            android == null ? null : new PlatformAsset(
                android.optString("assetName", ""),
                android.optString("downloadUrl", "")
            )
        );
    }

    public static Optional<UpdateCandidate> selectUpdateCandidate(
        String currentVersion,
        String lastPromptedVersion,
        ReleaseInfo release
    ) {
        if (release == null || release.draft || release.prerelease) {
            return Optional.empty();
        }
        String releaseVersion = normalizeVersion(release.tagName);
        if (releaseVersion == null || releaseVersion.contains("-")) {
            return Optional.empty();
        }
        if (releaseVersion.equals(lastPromptedVersion)) {
            return Optional.empty();
        }
        int[] current = parseStableSemver(currentVersion);
        int[] latest = parseStableSemver(releaseVersion);
        if (current == null || latest == null || compareSemver(latest, current) <= 0) {
            return Optional.empty();
        }

        ReleaseAsset exact = findExactApkAsset(release.assets, releaseVersion);
        ReleaseAsset asset = exact != null ? exact : findVersionedApkAsset(release.assets, releaseVersion);
        if (asset == null || asset.browserDownloadUrl.trim().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new UpdateCandidate(releaseVersion, asset.name, asset.browserDownloadUrl));
    }

    public static Optional<UpdateCandidate> selectUpdateCandidate(
        String currentVersion,
        String lastPromptedVersion,
        UpdateManifest manifest
    ) {
        if (manifest == null) {
            return Optional.empty();
        }
        String releaseVersion = normalizeVersion(manifest.version);
        if (releaseVersion == null || releaseVersion.contains("-")) {
            return Optional.empty();
        }
        if (releaseVersion.equals(lastPromptedVersion)) {
            return Optional.empty();
        }
        int[] current = parseStableSemver(currentVersion);
        int[] latest = parseStableSemver(releaseVersion);
        if (current == null || latest == null || compareSemver(latest, current) <= 0) {
            return Optional.empty();
        }

        PlatformAsset asset = manifest.android;
        if (asset == null) {
            return Optional.empty();
        }
        String assetName = asset.assetName.trim();
        String downloadUrl = asset.downloadUrl.trim();
        if (!assetName.toLowerCase(Locale.ROOT).endsWith(".apk") || downloadUrl.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new UpdateCandidate(releaseVersion, assetName, downloadUrl));
    }

    public static void startManualCheck(Context context, UpdateProgressListener listener) {
        Context appContext = context.getApplicationContext();
        Thread thread = new Thread(
            () -> checkForUpdate(appContext, listener),
            "smspusher-android-manual-update-checker"
        );
        thread.setDaemon(true);
        thread.start();
    }

    private static void checkForUpdate(Context context) {
        checkForUpdate(context, null);
    }

    private static void checkForUpdate(Context context, UpdateProgressListener listener) {
        notifyChecking(listener);
        try {
            SharedPreferences preferences = preferences(context);
            UpdateManifest manifest = parseManifest(fetchText(UPDATE_MANIFEST_URL));
            Optional<UpdateCandidate> candidate = selectUpdateCandidate(
                BuildConfig.VERSION_NAME,
                preferences.getString(KEY_LAST_PROMPTED_VERSION, null),
                manifest
            );
            if (!candidate.isPresent()) {
                notifyNoUpdate(listener);
                return;
            }
            promptForUpdate(context, preferences, candidate.get(), listener);
        } catch (Exception e) {
            LOGGER.warn("Android update check failed", e);
            notifyFailure(listener, e);
        }
    }

    private static void promptForUpdate(
        Context context,
        SharedPreferences preferences,
        UpdateCandidate candidate,
        UpdateProgressListener listener
    ) throws IOException {
        if (!canRequestPackageInstalls(context)) {
            if (!candidate.versionName.equals(preferences.getString(KEY_LAST_PERMISSION_PROMPT_VERSION, null))) {
                openUnknownSourcesSettings(context);
                preferences.edit().putString(KEY_LAST_PERMISSION_PROMPT_VERSION, candidate.versionName).apply();
            }
            notifyInstallPermissionRequired(listener);
            return;
        }

        File apk = downloadApk(context, candidate, listener);
        openInstaller(context, apk);
        preferences.edit()
            .putString(KEY_LAST_PROMPTED_VERSION, candidate.versionName)
            .remove(KEY_LAST_PERMISSION_PROMPT_VERSION)
            .apply();
        notifyInstallerOpened(listener, candidate.versionName);
    }

    private static String fetchText(String urlString) throws IOException {
        HttpURLConnection connection = openConnection(urlString);
        connection.setRequestProperty("Accept", "application/json");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("update manifest request failed with status " + status);
            }
            try (InputStream input = connection.getInputStream()) {
                return readUtf8(input);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static File downloadApk(
        Context context,
        UpdateCandidate candidate,
        UpdateProgressListener listener
    ) throws IOException {
        File directory = updateDirectory(context);
        File target = new File(directory, "SmsPusher-" + candidate.versionName + ".apk");
        File partial = new File(directory, target.getName() + ".download");
        if (partial.exists() && !partial.delete()) {
            throw new IOException("Unable to remove partial update download");
        }

        HttpURLConnection connection = openConnection(candidate.downloadUrl);
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("APK download failed with status " + status);
            }
            long total = connection.getContentLengthLong();
            long downloaded = 0L;
            int lastProgress = -1;
            notifyDownloading(listener, total > 0L ? 0 : -1);
            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(partial)) {
                byte[] buffer = new byte[16 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    downloaded += count;
                    if (total > 0L) {
                        int progress = (int) Math.min(100L, downloaded * 100L / total);
                        if (progress != lastProgress) {
                            lastProgress = progress;
                            notifyDownloading(listener, progress);
                        }
                    }
                }
            }
        } finally {
            connection.disconnect();
        }

        if (target.exists() && !target.delete()) {
            throw new IOException("Unable to replace existing update APK");
        }
        if (!partial.renameTo(target)) {
            throw new IOException("Unable to finish update APK download");
        }
        return target;
    }

    private static void notifyChecking(UpdateProgressListener listener) {
        if (listener != null) listener.onChecking();
    }

    private static void notifyNoUpdate(UpdateProgressListener listener) {
        if (listener != null) listener.onNoUpdate();
    }

    private static void notifyDownloading(UpdateProgressListener listener, int progressPercent) {
        if (listener != null) listener.onDownloading(progressPercent);
    }

    private static void notifyInstallerOpened(UpdateProgressListener listener, String versionName) {
        if (listener != null) listener.onInstallerOpened(versionName);
    }

    private static void notifyInstallPermissionRequired(UpdateProgressListener listener) {
        if (listener != null) listener.onInstallPermissionRequired();
    }

    private static void notifyFailure(UpdateProgressListener listener, Exception error) {
        if (listener != null) {
            String message = error.getMessage();
            listener.onFailure(message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message);
        }
    }

    private static HttpURLConnection openConnection(String urlString) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", "SmsPusher/" + BuildConfig.VERSION_NAME);
        return connection;
    }

    private static String readUtf8(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toString("UTF-8");
    }

    private static File updateDirectory(Context context) throws IOException {
        File directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (directory == null) {
            directory = new File(context.getFilesDir(), "updates");
        }
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Unable to create update download directory");
        }
        return directory;
    }

    private static boolean canRequestPackageInstalls(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return true;
        }
        return context.getPackageManager().canRequestPackageInstalls();
    }

    private static void openUnknownSourcesSettings(Context context) {
        Intent intent = new Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:" + context.getPackageName())
        );
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private static void openInstaller(Context context, File apk) {
        Uri uri = FileProvider.getUriForFile(
            context,
            context.getPackageName() + ".fileprovider",
            apk
        );
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, APK_MIME_TYPE);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(intent);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String normalizeVersion(String version) {
        if (version == null) {
            return null;
        }
        String trimmed = version.trim();
        if (trimmed.startsWith("v") || trimmed.startsWith("V")) {
            trimmed = trimmed.substring(1);
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static int[] parseStableSemver(String version) {
        String normalized = normalizeVersion(version);
        if (normalized == null || normalized.contains("-")) {
            return null;
        }
        String[] parts = normalized.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        int[] parsed = new int[3];
        for (int i = 0; i < parts.length; i++) {
            try {
                parsed[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return null;
            }
            if (parsed[i] < 0) {
                return null;
            }
        }
        return parsed;
    }

    private static int compareSemver(int[] left, int[] right) {
        for (int i = 0; i < left.length; i++) {
            int comparison = Integer.compare(left[i], right[i]);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    private static ReleaseAsset findExactApkAsset(List<ReleaseAsset> assets, String versionName) {
        String expectedName = "SmsPusher-" + versionName + ".apk";
        for (ReleaseAsset asset : assets) {
            if (expectedName.equals(asset.name)) {
                return asset;
            }
        }
        return null;
    }

    private static ReleaseAsset findVersionedApkAsset(List<ReleaseAsset> assets, String versionName) {
        for (ReleaseAsset asset : assets) {
            String lowerName = asset.name.toLowerCase(Locale.ROOT);
            if (lowerName.endsWith(".apk") && asset.name.contains(versionName)) {
                return asset;
            }
        }
        return null;
    }

    public static final class ReleaseInfo {
        public final String tagName;
        public final boolean draft;
        public final boolean prerelease;
        public final List<ReleaseAsset> assets;

        public ReleaseInfo(String tagName, boolean draft, boolean prerelease, List<ReleaseAsset> assets) {
            this.tagName = tagName;
            this.draft = draft;
            this.prerelease = prerelease;
            this.assets = Collections.unmodifiableList(new ArrayList<>(assets));
        }
    }

    public static final class ReleaseAsset {
        public final String name;
        public final String browserDownloadUrl;

        public ReleaseAsset(String name, String browserDownloadUrl) {
            this.name = name;
            this.browserDownloadUrl = browserDownloadUrl;
        }
    }

    public static final class UpdateManifest {
        public final String version;
        public final PlatformAsset android;

        public UpdateManifest(String version, PlatformAsset android) {
            this.version = version;
            this.android = android;
        }
    }

    public static final class PlatformAsset {
        public final String assetName;
        public final String downloadUrl;

        public PlatformAsset(String assetName, String downloadUrl) {
            this.assetName = assetName;
            this.downloadUrl = downloadUrl;
        }
    }

    public static final class UpdateCandidate {
        public final String versionName;
        public final String assetName;
        public final String downloadUrl;

        private UpdateCandidate(String versionName, String assetName, String downloadUrl) {
            this.versionName = versionName;
            this.assetName = assetName;
            this.downloadUrl = downloadUrl;
        }
    }

    public interface UpdateProgressListener {
        void onChecking();

        void onNoUpdate();

        void onDownloading(int progressPercent);

        void onInstallerOpened(String versionName);

        void onInstallPermissionRequired();

        void onFailure(String message);
    }
}
