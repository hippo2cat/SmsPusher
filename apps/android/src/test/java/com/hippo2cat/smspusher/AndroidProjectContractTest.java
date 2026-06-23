package com.hippo2cat.smspusher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class AndroidProjectContractTest {
    private static final Path APP_ROOT = findAppRoot();
    private static final Path REPO_ROOT = APP_ROOT.getParent().getParent();
    private static final Pattern LEGACY_IDENTITY = Pattern.compile(
        "com\\.jbz\\.(smspusher|androidsmspushtomacos|smsbridge)"
            + "|AndroidSmsPushToMacos|SMS Bridge|android:label=\"SMS Bridge\""
    );

    @Test
    public void applicationIdentityUsesCurrentPackageAndLauncherBranding() throws Exception {
        String build = readApp("build.gradle.kts");
        String manifest = readApp("src/main/AndroidManifest.xml");
        String strings = readApp("src/main/res/values/strings.xml");

        assertContains(build, "namespace = \"com.hippo2cat.smspusher\"");
        assertContains(build, "applicationId = \"com.hippo2cat.smspusher\"");
        assertContains(build, "setProperty(\"archivesBaseName\", \"SmsPusher\")");
        assertExists(app("src/main/java/com/hippo2cat/smspusher"));
        assertExists(app("src/test/java/com/hippo2cat/smspusher"));
        assertAbsent(app("src/main/java/com/jbz/smspusher"));
        assertAbsent(app("src/main/java/com/jbz/androidsmspushtomacos"));
        assertAbsent(app("src/main/java/com/jbz/smsbridge"));
        assertAbsent(app("src/test/java/com/jbz/smspusher"));
        assertAbsent(app("src/test/java/com/jbz/androidsmspushtomacos"));
        assertAbsent(app("src/test/java/com/jbz/smsbridge"));

        assertContains(manifest, "android:label=\"@string/app_name\"");
        assertContains(manifest, "android:icon=\"@mipmap/ic_launcher\"");
        assertContains(manifest, "android:roundIcon=\"@mipmap/ic_launcher_round\"");
        assertContains(strings, "<string name=\"app_name\">SmsPusher</string>");
        for (String density : List.of("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")) {
            assertExists(app("src/main/res/mipmap-" + density + "/ic_launcher.png"));
            assertExists(app("src/main/res/mipmap-" + density + "/ic_launcher_round.png"));
        }
        assertNoLegacyIdentityUnder(app("src/main"));
    }

    @Test
    public void manifestDeclaresSmsNetworkForegroundAndStartupCapabilities() throws Exception {
        String manifest = readApp("src/main/AndroidManifest.xml");

        assertContains(manifest, "android:usesCleartextTraffic=\"true\"");
        assertContains(manifest, "android.permission.INTERNET");
        assertContains(manifest, "android.permission.ACCESS_NETWORK_STATE");
        assertContains(manifest, "android.permission.CHANGE_WIFI_MULTICAST_STATE");
        assertContains(manifest, "android.permission.RECEIVE_SMS");
        assertContains(manifest, "android.permission.READ_SMS");
        assertContains(manifest, "android.permission.POST_NOTIFICATIONS");
        assertContains(manifest, "android.permission.FOREGROUND_SERVICE");
        assertContains(manifest, "android.permission.FOREGROUND_SERVICE_REMOTE_MESSAGING");
        assertContains(manifest, "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS");
        assertContains(manifest, "android.permission.RECEIVE_BOOT_COMPLETED");
        assertContains(manifest, "android.permission.RECEIVE_SENSITIVE_NOTIFICATIONS");
        assertContains(manifest, "com.miui.securitycenter.permission.SYSTEM_PERMISSION_DECLARE");
        assertContains(manifest, "android:name=\".SmsListenerService\"");
        assertContains(manifest, "android:foregroundServiceType=\"remoteMessaging\"");
        assertContains(manifest, "android:name=\".AppStartupReceiver\"");
        assertContains(manifest, "android.intent.action.BOOT_COMPLETED");
        assertContains(manifest, "android.intent.action.MY_PACKAGE_REPLACED");
        assertFalse(manifest.contains("android.intent.action.LOCKED_BOOT_COMPLETED"));
    }

    @Test
    public void androidDiagnosticsUseLogbackFileLogging() throws Exception {
        String build = readApp("build.gradle.kts");
        String manifest = readApp("src/main/AndroidManifest.xml");
        String logging = readApp("src/main/java/com/hippo2cat/smspusher/logging/AppLogging.java");
        String exporter = readApp("src/main/java/com/hippo2cat/smspusher/logging/DiagnosticLogExporter.java");
        String activity = readApp("src/main/java/com/hippo2cat/smspusher/MainActivity.java");
        String logback = readApp("src/main/assets/logback.xml");

        assertContains(build, "org.slf4j:slf4j-api");
        assertContains(build, "com.github.tony19:logback-android");
        assertContains(manifest, "android:name=\".SmsPusherApplication\"");
        assertContains(logging, "System.setProperty(\"SM_PUSHER_LOG_DIR\"");
        assertContains(logback, "RollingFileAppender");
        assertContains(logback, "${SM_PUSHER_LOG_DIR}/smspusher.log");
        assertContains(logback, "LogcatAppender");
        assertContains(exporter, "collectRecentLogs");
        assertContains(activity, "DiagnosticLogExporter.recentLogText(this)");
    }

    @Test
    public void releaseSigningUsesOperationalScriptsButIsGuardedByGradle() throws Exception {
        String gitignore = readRepo(".gitignore");
        String build = readApp("build.gradle.kts");

        assertContains(gitignore, "apps/android/keystore.properties");
        assertContains(gitignore, "apps/android/release/");
        assertContains(build, "keystore.properties");
        assertContains(build, "signingConfigs");
        assertContains(build, "assembleRelease");
        assertContains(build, "hasReleaseSigningConfig");
        assertExists(app("scripts/build-android-release.sh"));
        assertExists(app("scripts/generate-android-release-keystore.sh"));
        assertTrue("release build helper should be executable", Files.isExecutable(app("scripts/build-android-release.sh")));
        assertTrue("keystore helper should be executable", Files.isExecutable(app("scripts/generate-android-release-keystore.sh")));
    }

    @Test
    public void androidReleaseApkUpdatesAreWiredToSystemInstaller() throws Exception {
        String build = readApp("build.gradle.kts");
        String manifest = readApp("src/main/AndroidManifest.xml");
        String activity = readApp("src/main/java/com/hippo2cat/smspusher/MainActivity.java");
        String updater = readApp("src/main/java/com/hippo2cat/smspusher/update/AndroidUpdateChecker.java");
        String filePaths = readApp("src/main/res/xml/apk_file_paths.xml");
        String workflow = readRepo(".github/workflows/android-release.yml");

        assertContains(workflow, "SmsPusher-${VERSION_NAME}.apk");
        assertContains(build, "androidx.core:core");
        assertContains(manifest, "android.permission.REQUEST_INSTALL_PACKAGES");
        assertContains(manifest, "androidx.core.content.FileProvider");
        assertContains(manifest, "android:authorities=\"${applicationId}.fileprovider\"");
        assertContains(manifest, "android.support.FILE_PROVIDER_PATHS");
        assertContains(manifest, "@xml/apk_file_paths");
        assertContains(filePaths, "<external-files-path");
        assertContains(filePaths, "path=\"Download/\"");
        assertContains(filePaths, "<files-path");
        assertContains(filePaths, "path=\"updates/\"");

        assertContains(activity, "AndroidUpdateChecker.start(this)");
        assertContains(updater, "BuildConfig.VERSION_NAME");
        assertContains(updater, "https://api.github.com/repos/hippo2cat/");
        assertContains(updater, "AndroidSmsPushTo");
        assertContains(updater, "Macos/releases/latest");
        assertContains(updater, "SmsPusher-");
        assertContains(updater, "getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)");
        assertContains(updater, "Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES");
        assertContains(updater, "FileProvider.getUriForFile");
        assertContains(updater, "application/vnd.android.package-archive");
    }

    @Test
    public void backgroundListenerReliabilityIsWiredThroughAndroidComponents() throws Exception {
        String receiver = readApp("src/main/java/com/hippo2cat/smspusher/SmsReceiver.java");
        String activity = readApp("src/main/java/com/hippo2cat/smspusher/MainActivity.java");
        String service = readApp("src/main/java/com/hippo2cat/smspusher/SmsListenerService.java");
        String health = readApp("src/main/java/com/hippo2cat/smspusher/state/ServiceHealthStore.java");
        String startup = readApp("src/main/java/com/hippo2cat/smspusher/AppStartupReceiver.java");
        String policy = readApp("src/main/java/com/hippo2cat/smspusher/sms/SmsInboxScanPolicy.java");

        assertContains(receiver, "PendingResult result = goAsync();");
        assertContains(receiver, "DeliveryWorker.enqueueFromSmsIntent(appContext, intent);");
        assertContains(receiver, "result.finish();");
        assertContains(activity, "PowerManager");
        assertContains(activity, "isIgnoringBatteryOptimizations");
        assertContains(activity, "Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS");
        assertContains(activity, "ServiceHealthStore.isHealthy");
        assertContains(activity, "ServiceHealthStore.isNetworkAvailable(this)");
        assertContains(health, "lastHeartbeatAt");
        assertContains(health, "isHealthy");
        assertContains(health, "recordNetworkAvailable");
        assertContains(health, "recordNetworkLost");

        assertContains(service, "startForeground");
        assertContains(service, "START_STICKY");
        assertContains(service, "FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING");
        assertContains(service, "ServiceHealthStore.recordHeartbeat");
        assertContains(service, "ConnectivityManager.NetworkCallback");
        assertContains(service, "registerDefaultNetworkCallback");
        assertContains(service, "unregisterNetworkCallback");
        assertContains(service, "requestInboxSync(\"network_available\")");
        assertContains(service, "drainPendingQueue(\"network_available\")");
        assertContains(service, "retryQueue");
        assertContains(service, "SmsInboxScanPolicy.QUEUE_RETRY_INTERVAL_MS");
        assertContains(service, "DeliveryWorker.drainAsync");

        assertContains(policy, "POLL_INTERVAL_MS = 5_000L");
        assertContains(policy, "QUEUE_RETRY_INTERVAL_MS = 60_000L");
        assertContains(startup, "SecureTokenStore");
        assertContains(startup, "PairingStore.loadMacBaseUrl");
        assertContains(startup, "SmsListenerService.start");
        assertFalse(startup.contains("ACTION_LOCKED_BOOT_COMPLETED"));
    }

    @Test
    public void securePairingClientIdentityAndEndpointRecoveryAreConnected() throws Exception {
        String activity = readApp("src/main/java/com/hippo2cat/smspusher/MainActivity.java");
        String identity = readApp("src/main/java/com/hippo2cat/smspusher/state/ClientIdentityStore.java");
        String client = readApp("src/main/java/com/hippo2cat/smspusher/net/SmsBridgeClient.java");
        String secureMessage = readApp("src/main/java/com/hippo2cat/smspusher/net/SecureMessageClient.java");
        String securePairing = readApp("src/main/java/com/hippo2cat/smspusher/net/SecurePairingClient.java");
        String discovery = readApp("src/main/java/com/hippo2cat/smspusher/discovery/MacDiscoveryService.java");
        String resolver = readApp("src/main/java/com/hippo2cat/smspusher/discovery/NsdMacEndpointResolver.java");
        String session = readApp("src/main/java/com/hippo2cat/smspusher/delivery/DeliverySession.java");
        String worker = readApp("src/main/java/com/hippo2cat/smspusher/delivery/DeliveryWorker.java");
        String desktopHttp = readRepo("services/desktop-rust/crates/transport-lan/src/http.rs");

        assertContains(activity, "ClientIdentityStore.clientInstanceId(this)");
        assertContains(activity, "client.fetchPairingSession()");
        assertContains(activity, "endpoint.withSecureSession");
        assertContains(activity, "client.pairSecure(");
        assertContains(activity, "PairingEndpoint.discovered");
        assertContains(identity, "PREFS = \"sms_bridge_client_identity\"");
        assertContains(identity, "getSharedPreferences(PREFS");
        assertContains(identity, "UUID.randomUUID().toString()");
        assertContains(client, "clientInstanceId");
        assertContains(client, "SecurePairingClient");
        assertContains(client, "verifyPairing(PairingCredential credential)");
        assertContains(client, "new SecureMessageClient(baseUrl, transport).verifyPairing(credential)");
        assertContains(secureMessage, "\"/secure/auth/check\"");
        assertContains(secureMessage, "\"/secure/messages\"");
        assertContains(securePairing, "\"/pair/v2/start\"");
        assertContains(securePairing, "\"/pair/v2/finish\"");
        assertContains(desktopHttp, "\"/secure/auth/check\"");

        assertExists(app("src/main/java/com/hippo2cat/smspusher/state/PairingEndpoint.java"));
        assertExists(app("src/main/java/com/hippo2cat/smspusher/discovery/MacEndpointResolver.java"));
        assertExists(app("src/main/java/com/hippo2cat/smspusher/discovery/MacEndpointResolution.java"));
        assertContains(discovery, "isUsableResolvedService");
        assertContains(discovery, "MacEndpointUrls.baseUrl(resolved) != null");
        assertContains(resolver, "ExecutorService verificationExecutor");
        assertContains(resolver, "verificationExecutor.execute");
        assertContains(resolver, "client.verifyPairing(credential)");
        assertContains(resolver, "current.serviceName.isEmpty() || current.serviceName.equals(serviceInfo.getServiceName())");
        assertFalse(resolver.contains("if (!current.serviceName.equals(serviceInfo.getServiceName())) return;"));
        assertContains(session, "recoverEndpoint");
        assertContains(worker, "NsdMacEndpointResolver");
        assertContains(worker, "AtomicBoolean");
        assertContains(worker, "DRAIN_RUNNING.compareAndSet(false, true)");
        assertContains(worker, "DRAIN_RUNNING.set(false)");
        assertContains(readApp("src/test/java/com/hippo2cat/smspusher/delivery/DeliverySessionTest.java"), "networkFailureRecoversEndpointAndRetriesCurrentMessage");
    }

    @Test
    public void pairingCredentialAndEndpointStoresCommitCriticalPairingStateSynchronously() throws Exception {
        String pairingStore = readApp("src/main/java/com/hippo2cat/smspusher/state/PairingStore.java");
        String secureTokenStore = readApp("src/main/java/com/hippo2cat/smspusher/auth/SecureTokenStore.java");

        assertContains(pairingStore, ".commit()");
        assertContains(secureTokenStore, ".commit()");
        assertFalse("PairingStore must not use async apply for critical pairing endpoint state", pairingStore.contains(".apply();"));
        assertFalse("SecureTokenStore must not use async apply for critical pairing credentials", secureTokenStore.contains(".apply();"));
    }

    @Test
    public void smsInboxSyncAndDeliveryQueueRecordUserVisibleMessageActivity() throws Exception {
        String manifest = readApp("src/main/AndroidManifest.xml");
        String service = readApp("src/main/java/com/hippo2cat/smspusher/SmsListenerService.java");
        String synchronizer = readApp("src/main/java/com/hippo2cat/smspusher/sms/SmsInboxSynchronizer.java");
        String worker = readApp("src/main/java/com/hippo2cat/smspusher/delivery/DeliveryWorker.java");
        String activity = readApp("src/main/java/com/hippo2cat/smspusher/MainActivity.java");
        String messageList = readApp("src/main/java/com/hippo2cat/smspusher/ui/MessageListUiState.java");

        assertContains(manifest, "android.permission.READ_SMS");
        assertContains(service, "SmsInboxSynchronizer.sync");
        assertContains(synchronizer, "Telephony.Sms.Inbox.CONTENT_URI");
        assertContains(synchronizer, "DeliveryWorker.enqueueIncomingSms");
        assertContains(worker, "ProcessedSmsStore");
        assertContains(worker, ".claim(messageId)");
        assertContains(worker, "MessageEventStore");
        assertContains(worker, "recordPending");
        assertContains(worker, "recordForwardedMessage");
        assertContains(worker, "recordFailedMessage");

        assertContains(activity, "DeliveryWorker.pendingMessages");
        assertContains(activity, "MessageListUiState.from");
        assertContains(activity, "MessageActivityUiState.from");
        assertContains(activity, "recentActivity(100)");
        assertContains(activity, "renderPendingMessagesScreen");
        assertContains(activity, "renderActivityCard");
        assertContains(activity, "prepareTaskBottomSurface");
        assertContains(activity, "statusPill");
        assertContains(activity, "statusDot");
        assertContains(activity, "R.string.android_message_push_now");
        assertContains(activity, "R.string.android_message_all_forwarded");
        assertContains(activity, "R.string.android_message_view_messages");
        assertContains(activity, "R.string.android_message_recent_activity");
    }

    @Test
    public void permissionUiUsesRuntimeTogglesAndMiuiRowsOnlyWhenRelevant() throws Exception {
        String activity = readApp("src/main/java/com/hippo2cat/smspusher/MainActivity.java");
        String homeState = readApp("src/main/java/com/hippo2cat/smspusher/ui/HomeUiState.java");
        String policy = readApp("src/main/java/com/hippo2cat/smspusher/permission/RuntimePermissionRequestPolicy.java");
        String miuiGuide = readApp("src/main/java/com/hippo2cat/smspusher/miui/MiuiPermissionGuide.java");
        String miuiRequester = readApp("src/main/java/com/hippo2cat/smspusher/miui/MiuiPermissionRequester.java");

        assertContains(activity, "import android.widget.Switch;");
        assertContains(activity, "PermissionToggleUiState.runtime");
        assertContains(activity, "RuntimePermissionRequestPolicy.actionForToggle");
        assertContains(activity, "addPermissionToggleRow");
        assertContains(activity, "setOnCheckedChangeListener");
        assertContains(activity, "buttonView.post");
        assertContains(activity, "Manifest.permission.RECEIVE_SMS");
        assertContains(activity, "Manifest.permission.READ_SMS");
        assertContains(activity, "Manifest.permission.POST_NOTIFICATIONS");
        assertContains(activity, "openAppPermissionSettings");
        assertContains(activity, "Settings.ACTION_APPLICATION_DETAILS_SETTINGS");
        assertContains(activity, "if (state.showMiuiPermission)");
        assertContains(activity, "MiuiPermissionRequester.promptIfNeeded(this)");
        assertContains(policy, "REQUEST_RUNTIME_PERMISSION");
        assertFalse(policy.contains("OPEN_APP_PERMISSION_SETTINGS"));
        assertFalse(activity.contains("RuntimePermissionRequestPolicy.actionAfterDenied"));
        assertFalse(activity.contains("openPermissionSettingsFallbackIfNeeded"));

        assertContains(homeState, "showMiuiPermission");
        assertContains(homeState, "miuiDevice");
        assertContains(miuiGuide, "NOTIFICATION_SMS_APP_OP = 10018");
        assertContains(miuiRequester, "checkOpNoThrow");
        assertContains(miuiRequester, "miui.intent.action.APP_PERM_EDITOR");
        assertContains(miuiRequester, "openBackgroundSettings");
    }

    private static Path findAppRoot() {
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        if (Files.exists(cwd.resolve("src/main/AndroidManifest.xml"))) {
            return cwd;
        }
        return cwd.resolve("apps/android").normalize();
    }

    private static Path app(String relativePath) {
        return APP_ROOT.resolve(relativePath).normalize();
    }

    private static Path repo(String relativePath) {
        return REPO_ROOT.resolve(relativePath).normalize();
    }

    private static String readApp(String relativePath) throws IOException {
        return read(app(relativePath));
    }

    private static String readRepo(String relativePath) throws IOException {
        return read(repo(relativePath));
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static void assertExists(Path path) {
        assertTrue(path + " should exist", Files.exists(path));
    }

    private static void assertAbsent(Path path) {
        assertFalse(path + " should not exist", Files.exists(path));
    }

    private static void assertContains(String haystack, String needle) {
        assertTrue("Expected to find: " + needle, haystack.contains(needle));
    }

    private static void assertNoLegacyIdentityUnder(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> textFiles = stream
                .filter(Files::isRegularFile)
                .filter(AndroidProjectContractTest::isTextSource)
                .collect(Collectors.toList());
            for (Path path : textFiles) {
                String source = read(path);
                assertFalse("Legacy identity found in " + path, LEGACY_IDENTITY.matcher(source).find());
            }
        }
    }

    private static boolean isTextSource(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".java")
            || name.endsWith(".kt")
            || name.endsWith(".kts")
            || name.endsWith(".xml")
            || name.endsWith(".properties")
            || name.endsWith(".toml");
    }
}
