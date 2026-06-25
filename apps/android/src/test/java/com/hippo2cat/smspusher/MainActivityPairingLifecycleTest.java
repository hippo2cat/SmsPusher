package com.hippo2cat.smspusher;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;

public final class MainActivityPairingLifecycleTest {
    @Test
    public void verifiedPairingRefreshesConnectionHealthBeforeRenderingHome() throws Exception {
        String source = new String(
            Files.readAllBytes(Paths.get("src/main/java/com/hippo2cat/smspusher/MainActivity.java")),
            StandardCharsets.UTF_8
        );
        String method = source
            .split("private void activateVerifiedPairing\\(String macBaseUrl\\)", 2)[1]
            .split("private void clearStoredPairing", 2)[0];

        int heartbeat = method.indexOf("ServiceHealthStore.recordHeartbeat(this)");
        int networkAvailable = method.indexOf("ServiceHealthStore.recordNetworkAvailable(this)");
        int refresh = method.indexOf("refreshConnectionStatus();");

        assertTrue("activateVerifiedPairing should record a fresh heartbeat", heartbeat >= 0);
        assertTrue("activateVerifiedPairing should record current network availability", networkAvailable >= 0);
        assertTrue("heartbeat should be recorded before refreshConnectionStatus", heartbeat < refresh);
        assertTrue("network availability should be recorded before refreshConnectionStatus", networkAvailable < refresh);
    }

    @Test
    public void foregroundAndRuntimePermissionResultsRefreshPermissionStateFromSystem() throws Exception {
        String source = new String(
            Files.readAllBytes(Paths.get("src/main/java/com/hippo2cat/smspusher/MainActivity.java")),
            StandardCharsets.UTF_8
        );
        String onResume = source
            .split("public void onResume\\(\\)", 2)[1]
            .split("private void renderHome", 2)[0];
        String permissionResult = source
            .split("public void onRequestPermissionsResult\\(int requestCode, String\\[] permissions, int\\[] grantResults\\)", 2)[1]
            .split("private void updateStatusFromStoredPairing", 2)[0];
        assertTrue(source.contains("private void refreshPermissionStateFromSystem()"));
        String refreshMethod = source
            .split("private void refreshPermissionStateFromSystem\\(\\)", 2)[1]
            .split("private void updateStatusFromStoredPairing", 2)[0];

        assertTrue(onResume.contains("refreshPermissionStateFromSystem();"));
        assertTrue(permissionResult.contains("refreshPermissionStateFromSystem();"));
        assertFalse(permissionResult.contains("updateStatusFromStoredPairing();"));
        assertFalse(permissionResult.contains("renderHome();"));
        assertTrue(refreshMethod.contains("updateStatusFromStoredPairing();"));
        assertTrue(refreshMethod.contains("renderHome();"));
        assertTrue(refreshMethod.contains("permissionStatus(missingRuntimePermissions())"));
    }

    @Test
    public void connectionStatusRefreshesAfterNetworkEventsNavigationAndPairingChanges() throws Exception {
        Path eventsPath = Paths.get("src/main/java/com/hippo2cat/smspusher/ConnectionStatusEvents.java");
        String source = new String(
            Files.readAllBytes(Paths.get("src/main/java/com/hippo2cat/smspusher/MainActivity.java")),
            StandardCharsets.UTF_8
        );
        String service = new String(
            Files.readAllBytes(Paths.get("src/main/java/com/hippo2cat/smspusher/SmsListenerService.java")),
            StandardCharsets.UTF_8
        );

        assertTrue("connection refresh events should be centralized", Files.exists(eventsPath));
        assertTrue(source.contains("private BroadcastReceiver connectionStatusRefreshReceiver;"));
        assertTrue(source.contains("registerConnectionStatusRefreshReceiver();"));
        assertTrue(source.contains("unregisterConnectionStatusRefreshReceiver();"));
        assertTrue(source.contains("ConnectionStatusEvents.ACTION_REFRESH"));
        assertTrue(source.contains("private void refreshConnectionStatus()"));
        assertTrue(source.contains("registerReceiver(connectionStatusRefreshReceiver"));
        assertTrue(source.contains("unregisterReceiver(connectionStatusRefreshReceiver)"));
        assertTrue(source.contains("ServiceHealthStore.isNetworkAvailable(this)"));
        assertTrue(source.contains("intent.getStringExtra(ConnectionStatusEvents.EXTRA_SOURCE)"));
        assertTrue(source.contains("refreshConnectionStatusFromEvent(source);"));

        assertTrue(service.contains("ConnectionStatusEvents.notifyChanged(this, \"service_start\")"));
        assertTrue(service.contains("ConnectionStatusEvents.notifyChanged(SmsListenerService.this, \"network_available\")"));
        assertTrue(service.contains("ServiceHealthStore.recordNetworkLost(SmsListenerService.this)"));
        assertTrue(service.contains("ConnectionStatusEvents.notifyChanged(SmsListenerService.this, \"network_lost\")"));

        String bottomNavButton = source
            .split("private LinearLayout bottomNavButton\\(String text, int iconRes, int tab\\)", 2)[1]
            .split("private void renderStatusStrip", 2)[0];
        assertTrue(bottomNavButton.contains("refreshConnectionStatus();"));

        String secondaryActions = source
            .split("private void renderHomeSecondaryActions\\(\\)", 2)[1]
            .split("private void addHomeSecondaryAction", 2)[0];
        assertTrue(secondaryActions.contains("refreshConnectionStatus();"));

        String pairInBackground = source
            .split("private void pairInBackground", 2)[1]
            .split("private void testConnection", 2)[0];
        assertTrue(pairInBackground.contains("fetchPairingSession()"));
        assertTrue(pairInBackground.contains("withSecureSession("));
        assertTrue(pairInBackground.contains("activateVerifiedPairing(baseUrl);"));
        assertFalse(pairInBackground.contains("updateStatusFromStoredPairing();"));

        String clearStoredPairing = source
            .split("private void clearStoredPairing\\(String message\\)", 2)[1]
            .split("private boolean hasReceiveSmsPermission", 2)[0];
        assertTrue(clearStoredPairing.contains("refreshConnectionStatus();"));
    }

    @Test
    public void networkAvailableEventsVerifyAndRecoverStoredPairing() throws Exception {
        String source = new String(
            Files.readAllBytes(Paths.get("src/main/java/com/hippo2cat/smspusher/MainActivity.java")),
            StandardCharsets.UTF_8
        );
        String eventRefresh = source
            .split("private void refreshConnectionStatusFromEvent\\(String source\\)", 2)[1]
            .split("private void scheduleConnectionStatusRefresh", 2)[0];
        String verify = source
            .split("private void verifyStoredPairingAsync\\(PairingCredential credential, String macBaseUrl\\)", 2)[1]
            .split("private void activateVerifiedPairing", 2)[0];
        String recovery = source
            .split("private void recoverEndpointAfterVerificationFailure\\(PairingCredential fallbackCredential\\)", 2)[1]
            .split("private void verifyStoredPairingAsync", 2)[0];

        assertTrue(eventRefresh.contains("\"network_available\".equals(source)"));
        assertTrue(eventRefresh.contains("updateStatusFromStoredPairing();"));
        assertTrue(eventRefresh.contains("shouldDeferNetworkAvailablePairingVerification()"));
        assertTrue(eventRefresh.contains("scheduleConnectionStatusRefresh(shouldRenderConnectionStatusRefresh());"));
        assertTrue(verify.contains("recoverEndpointAfterVerificationFailure(credential);"));
        assertTrue(recovery.contains("new SecureTokenStore(this).loadCredential();"));
        assertTrue(recovery.contains("recoverMissingEndpointAsync(recoveryCredential);"));
    }

    @Test
    public void networkAvailableEventsDoNotDuplicateActiveOrRecentlyVerifiedPairingChecks() throws Exception {
        String source = new String(
            Files.readAllBytes(Paths.get("src/main/java/com/hippo2cat/smspusher/MainActivity.java")),
            StandardCharsets.UTF_8
        );
        String defer = source
            .split("private boolean shouldDeferNetworkAvailablePairingVerification\\(\\)", 2)[1]
            .split("private boolean wasPairingVerifiedRecently", 2)[0];
        String recent = source
            .split("private boolean wasPairingVerifiedRecently\\(\\)", 2)[1]
            .split("private void scheduleConnectionStatusRefresh", 2)[0];
        String activate = source
            .split("private void activateVerifiedPairing\\(String macBaseUrl\\)", 2)[1]
            .split("private void clearStoredPairing", 2)[0];
        String verify = source
            .split("private void verifyStoredPairingAsync\\(PairingCredential credential, String macBaseUrl\\)", 2)[1]
            .split("private void activateVerifiedPairing", 2)[0];

        assertTrue(source.contains("private static final long NETWORK_AVAILABLE_VERIFY_SUPPRESS_MS"));
        assertTrue(source.contains("private long lastPairingVerifiedAtMillis;"));
        assertTrue(defer.contains("pairingVerificationInFlight.get()"));
        assertTrue(defer.contains("endpointRecoveryInFlight"));
        assertTrue(defer.contains("wasPairingVerifiedRecently()"));
        assertTrue(recent.contains("SystemClock.elapsedRealtime() - lastPairingVerifiedAtMillis"));
        assertTrue(recent.contains("NETWORK_AVAILABLE_VERIFY_SUPPRESS_MS"));
        assertTrue(activate.contains("lastPairingVerifiedAtMillis = SystemClock.elapsedRealtime();"));
        assertTrue(verify.contains("pairing verification skipped"));
        assertTrue(verify.contains("pairingVerificationInFlight.compareAndSet(false, true)"));
    }

    @Test
    public void storedCredentialWithoutEndpointAttemptsEndpointRecoveryBeforeRePairing() throws Exception {
        String source = new String(
            Files.readAllBytes(Paths.get("src/main/java/com/hippo2cat/smspusher/MainActivity.java")),
            StandardCharsets.UTF_8
        );
        String updateStatus = source
            .split("private void updateStatusFromStoredPairing\\(\\)", 2)[1]
            .split("private void verifyStoredPairingAsync", 2)[0];
        assertTrue(source.contains("private void recoverMissingEndpointAsync(PairingCredential credential)"));
        String recovery = source
            .split("private void recoverMissingEndpointAsync\\(PairingCredential credential\\)", 2)[1]
            .split("private void verifyStoredPairingAsync", 2)[0];

        assertTrue(updateStatus.contains("recoverMissingEndpointAsync(credential);"));
        assertTrue(recovery.contains("new NsdMacEndpointResolver(this).resolve("));
        assertTrue(recovery.contains("PairingStore.saveEndpoint(this, resolution.endpoint);"));
        assertTrue(recovery.contains("new SecureTokenStore(this).saveCredential(resolution.credential);"));
        assertTrue(recovery.contains("activateVerifiedPairing(resolution.endpoint.baseUrl);"));
    }

    @Test
    public void discoveredEndpointChangesTriggerVerifiedRecoveryWhileAppIsOpen() throws Exception {
        String source = new String(
            Files.readAllBytes(Paths.get("src/main/java/com/hippo2cat/smspusher/MainActivity.java")),
            StandardCharsets.UTF_8
        );
        String renderServices = source
            .split("private void renderServices\\(List<NsdServiceInfo> services\\)", 2)[1]
            .split("private void showPairingForm", 2)[0];
        String recovery = source
            .split("private void recoverStoredEndpointFromDiscoveredServices\\(List<NsdServiceInfo> services\\)", 2)[1]
            .split("private void verifyDiscoveredEndpointAsync", 2)[0];
        String verify = source
            .split("private void verifyDiscoveredEndpointAsync\\(PairingEndpoint candidate, PairingCredential credential\\)", 2)[1]
            .split("private void showPairingForm", 2)[0];

        assertTrue(renderServices.contains("recoverStoredEndpointFromDiscoveredServices(discoveredServices);"));
        assertTrue(recovery.contains("PairingStore.loadEndpoint(this)"));
        assertTrue(recovery.contains("new SecureTokenStore(this).loadCredential()"));
        assertTrue(recovery.contains("MacEndpointUrls.baseUrl(service)"));
        assertTrue(recovery.contains("verifyDiscoveredEndpointAsync(candidate, credential);"));
        assertTrue(verify.contains("SmsBridgeClient client = new SmsBridgeClient(candidate.baseUrl, new UrlConnectionTransport());"));
        assertTrue(verify.contains("reserveCredentialCounter(credential);"));
        assertTrue(verify.contains("PairingStore.saveEndpoint(this, candidate);"));
        assertTrue(verify.contains("activateVerifiedPairing(candidate.baseUrl);"));
    }

    @Test
    public void storedPairingVerificationRunsSingleFlight() throws Exception {
        String source = new String(
            Files.readAllBytes(Paths.get("src/main/java/com/hippo2cat/smspusher/MainActivity.java")),
            StandardCharsets.UTF_8
        );
        String verify = source
            .split("private void verifyStoredPairingAsync\\(PairingCredential credential, String macBaseUrl\\)", 2)[1]
            .split("private void activateVerifiedPairing", 2)[0];
        String clearStoredPairing = source
            .split("private void clearStoredPairing\\(String message\\)", 2)[1]
            .split("private boolean hasReceiveSmsPermission", 2)[0];

        assertTrue(source.contains("import java.util.concurrent.atomic.AtomicBoolean;"));
        assertTrue(source.contains("private static final AtomicBoolean pairingVerificationInFlight = new AtomicBoolean(false);"));
        assertTrue(verify.contains("if (credential == null)"));
        assertTrue(verify.contains("if (!pairingVerificationInFlight.compareAndSet(false, true))"));
        assertTrue(verify.contains("pairingVerificationInFlight.set(false);"));
        assertTrue(clearStoredPairing.contains("pairingVerificationInFlight.set(false);"));
        assertFalse(source.contains("private boolean pairingVerificationInFlight;"));
    }

    @Test
    public void pairingFlowUsesPairingCodeScreenInsteadOfLegacyPanel() throws Exception {
        String source = new String(
            Files.readAllBytes(Paths.get("src/main/java/com/hippo2cat/smspusher/MainActivity.java")),
            StandardCharsets.UTF_8
        );

        assertTrue(source.contains("renderPairingCodeScreen(pairingBaseUrl);"));
        assertTrue(source.contains("private void renderPairingCodeScreen(String baseUrl)"));
        assertTrue(source.contains("R.string.android_pairing_title"));
        assertTrue(source.contains("R.string.android_pairing_subtitle"));
        assertTrue(source.contains("R.string.android_pairing_confirm"));
        assertTrue(source.contains("R.string.android_pairing_with_device"));
        assertTrue(source.contains("TextView[] codeSlots = new TextView[6]"));
        assertFalse(source.contains("private void renderPairingPanel(String baseUrl)"));
        assertFalse(source.contains("title.setText(\"配对 Mac\")"));
        assertFalse(source.contains("Button cancel = ConsoleTheme.action(content, \"取消\")"));
    }

    @Test
    public void pairingCodeScreenDoesNotRenderBottomNavigation() throws Exception {
        String source = new String(
            Files.readAllBytes(Paths.get("src/main/java/com/hippo2cat/smspusher/MainActivity.java")),
            StandardCharsets.UTF_8
        );
        String showsBottomNavigation = source
            .split("private boolean showsBottomNavigation\\(\\)", 2)[1]
            .split("private LinearLayout bottomNavButton", 2)[0];

        assertTrue(showsBottomNavigation.contains("!isPairingCodeVisible()"));
        assertTrue(source.contains("if (showsBottomNavigation()) renderBottomNavigation();"));
    }

    @Test
    public void desktopDisplayNameDoesNotUseHardcodedMacBookFallback() throws Exception {
        String source = new String(
            Files.readAllBytes(Paths.get("src/main/java/com/hippo2cat/smspusher/MainActivity.java")),
            StandardCharsets.UTF_8
        );
        String displayName = source
            .split("private String macDisplayName\\(String macBaseUrl\\)", 2)[1]
            .split("private String shortMacDisplayName", 2)[0];
        String shortDisplayName = source
            .split("private String shortMacDisplayName\\(String macBaseUrl\\)", 2)[1]
            .split("private String macHost", 2)[0];

        assertTrue(displayName.contains("PairingStore.loadEndpoint(this)"));
        assertTrue(displayName.contains("credential.pairedDesktopName"));
        assertTrue(displayName.contains("R.string.android_connection_computer"));
        assertFalse(displayName.contains("\"MacBook Pro\""));
        assertFalse(displayName.contains("\"MacBook Air\""));
        assertFalse(shortDisplayName.contains("\"MacBook Pro\""));
        assertFalse(shortDisplayName.contains("\"MacBook Air\""));
        assertFalse(shortDisplayName.contains("indexOf(\"MacBook\")"));
        assertFalse(shortDisplayName.contains("indexOf(\"iMac\")"));
        assertFalse(shortDisplayName.contains("indexOf(\" (\")"));
        assertFalse(shortDisplayName.contains("substring("));
    }

    @Test
    public void longDesktopDisplayNameStaysSingleLineInHomeHeader() throws Exception {
        String source = new String(
            Files.readAllBytes(Paths.get("src/main/java/com/hippo2cat/smspusher/MainActivity.java")),
            StandardCharsets.UTF_8
        );
        String deviceStatus = source
            .split("private void renderDeviceStatusCard\\(boolean paired, boolean permissionsOk, String macBaseUrl\\)", 2)[1]
            .split("private void connectedDesktopNameLabel", 2)[0];
        String nameLabel = source
            .split("private TextView connectedDesktopNameLabel\\(String macBaseUrl\\)", 2)[1]
            .split("private LinearLayout deviceActionRow", 2)[0];

        assertFalse(deviceStatus.contains("\"已连接到\\n\""));
        assertTrue(deviceStatus.contains("connectedDesktopNameLabel(macBaseUrl)"));
        assertTrue(nameLabel.contains("shortMacDisplayName(macBaseUrl)"));
        assertTrue(nameLabel.contains("singleLine("));
        assertTrue(nameLabel.contains("setGravity(Gravity.CENTER)"));
    }

    @Test
    public void pairingCodeDeleteClearsOneDigitPerBackspace() throws Exception {
        String source = new String(
            Files.readAllBytes(Paths.get("src/main/java/com/hippo2cat/smspusher/MainActivity.java")),
            StandardCharsets.UTF_8
        );
        String pairingCode = source
            .split("private void renderPairingCodeScreen\\(String baseUrl\\)", 2)[1]
            .split("private EditText pairingCodeInput", 2)[0];
        String deleteHandler = source
            .split("private boolean handlePairingCodeDelete\\(EditText\\[] codeInputs, int index, Button confirm, TextView error\\)", 2)[1]
            .split("private EditText pairingCodeInput", 2)[0];

        assertTrue(pairingCode.contains("handlePairingCodeDelete(codeInputs, index, confirm, error);"));
        assertTrue(deleteHandler.contains("current.getText().clear();"));
        assertTrue(deleteHandler.contains("previous.getText().clear();"));
        assertTrue(deleteHandler.contains("setPairingButtonEnabled(confirm, collectPairingCode(codeInputs).length() == 6);"));
        assertFalse(pairingCode.contains("codeInputs[index - 1].setSelection(codeInputs[index - 1].getText().length());"));
    }

    @Test
    public void pairingCodeScreenShowsNumericKeyboardOnEntry() throws Exception {
        String source = new String(
            Files.readAllBytes(Paths.get("src/main/java/com/hippo2cat/smspusher/MainActivity.java")),
            StandardCharsets.UTF_8
        );
        String pairingCode = source
            .split("private void renderPairingCodeScreen\\(String baseUrl\\)", 2)[1]
            .split("private boolean handlePairingCodeDelete", 2)[0];
        String keyboardHelper = source
            .split("private void showPairingKeyboard\\(EditText input\\)", 2)[1]
            .split("private boolean handlePairingCodeDelete", 2)[0];

        assertTrue(pairingCode.contains("showPairingKeyboard(codeInputs[0]);"));
        assertTrue(source.contains("import android.view.inputmethod.InputMethodManager;"));
        assertTrue(keyboardHelper.contains("input.requestFocus();"));
        assertTrue(keyboardHelper.contains("InputMethodManager.SHOW_IMPLICIT"));
        assertTrue(keyboardHelper.contains("showSoftInput(input"));
        assertTrue(keyboardHelper.contains("input.post("));
    }

    @Test
    public void manualPairingUsesDedicatedScreenBeforePairingCode() throws Exception {
        String source = new String(
            Files.readAllBytes(Paths.get("src/main/java/com/hippo2cat/smspusher/MainActivity.java")),
            StandardCharsets.UTF_8
        );

        assertTrue(source.contains("VIEW_MANUAL_PAIRING"));
        assertTrue(source.contains("renderManualPairingScreen()"));
        assertTrue(source.contains("R.string.android_manual_title"));
        assertTrue(source.contains("R.string.android_manual_subtitle"));
        assertTrue(source.contains("R.string.android_manual_host"));
        assertTrue(source.contains("manualPairingInput("));
        assertTrue(source.contains("R.string.android_manual_port"));
        assertTrue(source.contains("\"55515\""));
        assertTrue(source.contains("R.string.android_manual_continue"));
        assertTrue(source.contains("validateManualPairingEndpoint"));
        assertTrue(source.contains("showPairingForm(baseUrl);"));
        assertFalse(source.contains("manualInputPanel()"));
        assertFalse(source.contains("\"测试并配对\""));
    }

    @Test
    public void rePairOpensDeviceSelectionWithoutOpeningPairingCode() throws Exception {
        String source = new String(
            Files.readAllBytes(Paths.get("src/main/java/com/hippo2cat/smspusher/MainActivity.java")),
            StandardCharsets.UTF_8
        );

        assertTrue(source.contains("VIEW_DEVICE_SELECTION"));
        assertTrue(source.contains("renderDeviceSelectionScreen()"));
        assertTrue(source.contains("openDeviceSelection()"));
        assertTrue(source.contains("clearStoredPairingForDeviceSelection()"));
        assertFalse(source.contains("clearStoredPairingForRePair"));
        assertFalse(source.contains("showPairingForm(macBaseUrl);"));
        assertFalse(source.contains("confirmRePair(macBaseUrl)"));
        assertFalse(source.contains(".setPositiveButton(\"清除并重配\""));
    }

    @Test
    public void deviceSelectionRoutesDiscoveredAndManualPairing() throws Exception {
        String source = new String(
            Files.readAllBytes(Paths.get("src/main/java/com/hippo2cat/smspusher/MainActivity.java")),
            StandardCharsets.UTF_8
        );

        assertTrue(source.contains("private void renderDeviceSelectionScreen()"));
        assertTrue(source.contains("R.string.android_discovery_searching"));
        assertTrue(source.contains("R.string.android_discovery_same_wifi_hint"));
        assertTrue(source.contains("R.string.android_discovery_tap_to_pair"));
        assertTrue(source.contains("R.string.android_discovery_manual"));
        assertTrue(source.contains("R.string.android_discovery_empty"));
        assertTrue(source.contains("activeTab == VIEW_DEVICE_SELECTION"));

        String deviceStatus = source
            .split("private void renderDeviceStatusCard\\(boolean paired, boolean permissionsOk, String macBaseUrl\\)", 2)[1]
            .split("private void openDeviceSelection", 2)[0];
        assertTrue(deviceStatus.contains("openDeviceSelection();"));
        assertTrue(deviceStatus.contains("clearStoredPairingForDeviceSelection();"));
        assertFalse(deviceStatus.contains("activeTab = VIEW_MANUAL_PAIRING;"));

        String deviceSelection = source
            .split("private void renderDeviceSelectionScreen\\(\\)", 2)[1]
            .split("private LinearLayout deviceSelectionRow", 2)[0];
        assertTrue(deviceSelection.contains("showPairingForm(endpointFromService(service, baseUrl))"));
        assertTrue(deviceSelection.contains("activeTab = VIEW_MANUAL_PAIRING;"));
    }

    @Test
    public void homePageKeepsOnlyFigmaPrimaryAndSecondarySections() throws Exception {
        String source = new String(
            Files.readAllBytes(Paths.get("src/main/java/com/hippo2cat/smspusher/MainActivity.java")),
            StandardCharsets.UTF_8
        );
        String deviceScreen = source
            .split("private void renderDeviceScreen\\(boolean paired, String macBaseUrl, PairingCredential credential\\)", 2)[1]
            .split("private void renderHomeSecondaryActions", 2)[0];

        assertTrue(source.contains("private void renderHomeSecondaryActions()"));
        assertTrue(source.contains("R.string.android_home_connection_details"));
        assertTrue(source.contains("R.string.android_home_view_technical_status"));
        assertTrue(source.contains("R.string.android_home_recent_activity"));
        assertTrue(source.contains("if (paired) renderHomeSecondaryActions();"));
        assertTrue(source.contains("R.string.android_home_connect_mac"));
        assertFalse(deviceScreen.contains("renderConnectionTab"));
        assertFalse(deviceScreen.contains("renderActionGrid"));
        assertFalse(deviceScreen.contains("renderConnectionRows"));
        assertFalse(source.contains("tab == TAB_DEVICES && active"));
    }

    @Test
    public void homePageUsesFigmaCardNavigationAndActionMetrics() throws Exception {
        String source = new String(
            Files.readAllBytes(Paths.get("src/main/java/com/hippo2cat/smspusher/MainActivity.java")),
            StandardCharsets.UTF_8
        );
        String deviceScreen = source
            .split("private void renderDeviceScreen\\(boolean paired, String macBaseUrl, PairingCredential credential\\)", 2)[1]
            .split("private void renderDeviceStatusCard", 2)[0];
        String deviceStatus = source
            .split("private void renderDeviceStatusCard\\(boolean paired, boolean permissionsOk, String macBaseUrl\\)", 2)[1]
            .split("private void openDeviceSelection", 2)[0];
        String secondaryAction = source
            .split("private LinearLayout deviceActionRow\\(int icon, String title, String subtitle\\)", 2)[1]
            .split("private LinearLayout heroTopologyNode", 2)[0];
        String bottomNavigation = source
            .split("private void renderBottomNavigation\\(\\)", 2)[1]
            .split("private boolean showsBottomNavigation", 2)[0];
        String bottomNavButton = source
            .split("private LinearLayout bottomNavButton\\(String text, int iconRes, int tab\\)", 2)[1]
            .split("private void renderStatusStrip", 2)[0];

        assertTrue(deviceScreen.contains("ConsoleTheme.dp(content, 12), 0, ConsoleTheme.dp(content, 12), 0"));
        assertTrue(deviceStatus.contains("card.setPadding(ConsoleTheme.dp(content, 18), ConsoleTheme.dp(content, 32), ConsoleTheme.dp(content, 18), ConsoleTheme.dp(content, 24));"));
        assertTrue(deviceStatus.contains("new LinearLayout.LayoutParams(\n            ViewGroup.LayoutParams.MATCH_PARENT,\n            ViewGroup.LayoutParams.WRAP_CONTENT\n        )"));
        assertFalse(deviceStatus.contains("ConsoleTheme.dp(content, 525)"));
        assertTrue(deviceStatus.contains("ConsoleTheme.dp(content, 184), ConsoleTheme.dp(content, 49)"));
        assertTrue(deviceStatus.contains("DeviceTopologySizing.Layout topologySizing = DeviceTopologySizing.forScreenWidthDp(currentScreenWidthDp());"));
        assertTrue(deviceStatus.contains("DeviceTopologyConnectorMotion connectorMotion = DeviceTopologyConnectorMotion.from(paired, connected);"));
        assertTrue(deviceStatus.contains("heroTopologyNode(R.drawable.ic_bridge_phone, getString(R.string.android_home_current_phone), 64, 110, 30, 0)"));
        assertTrue(deviceStatus.contains("heroConnector(paired, topologySizing.connectorWidthDp, connectorMotion)"));
        assertTrue(deviceStatus.contains("topologySizing.laptopFrameWidthDp"));
        assertTrue(deviceStatus.contains("topologySizing.laptopBaseWidthDp"));
        assertTrue(secondaryAction.contains("ConsoleTheme.dp(content, 76)"));
        assertTrue(secondaryAction.contains("actionIconFrame(icon, ConsoleTheme.ACCENT_TEAL, 40, 22)"));
        assertTrue(bottomNavigation.contains("ConsoleTheme.dp(content, pairingCodeVisible ? 64 : 64)"));
        assertTrue(source.contains("private int bottomHostBottomPadding(boolean whiteFlowVisible)"));
        assertTrue(source.contains("systemBottomInset"));
        assertTrue(source.contains("bottomHostBottomPadding(whiteFlowVisible)"));
        assertTrue(source.contains("bottomHostHorizontalPadding(currentWhiteFlowVisible)"));
        assertTrue(source.contains("private LinearLayout bottomNavContentInsetWrapper()"));
        assertTrue(source.contains("bottomHostBackgroundColor(whiteFlowVisible)"));
        assertTrue(source.contains("bottomNavigationBarColor(whiteFlowVisible)"));
        assertTrue(source.contains("private int bottomNavStrokeWidth()"));
        assertTrue(source.contains("private int bottomNavStrokeColor()"));
        assertTrue(bottomNavigation.contains("bottomNavStrokeColor()"));
        assertTrue(bottomNavigation.contains("bottomNavStrokeWidth()"));
        assertFalse(source.contains("bottomNavHost.addView(ConsoleTheme.divider(content)"));
        assertTrue(bottomNavButton.contains("labelParams.setMargins(0, ConsoleTheme.dp(content, 1), 0, 0);"));
        assertTrue(source.contains("private boolean isPairingCodeVisible()"));
        assertTrue(source.contains("if (isPairingCodeVisible()) return Math.max(ConsoleTheme.dp(content, 8), systemBottomInset / 2);"));
        assertTrue(source.contains("if (whiteFlowVisible) return 0;"));
        assertTrue(source.contains("if (showsBottomNavigation()) return Math.max(ConsoleTheme.dp(content, 8), systemBottomInset / 2);"));
        assertTrue(source.contains("Math.max(ConsoleTheme.dp(content, 8), systemBottomInset)"));
    }

    @Test
    public void messagesTabUsesFigmaStatusCardAndPendingListRoute() throws Exception {
        String source = new String(
            Files.readAllBytes(Paths.get("src/main/java/com/hippo2cat/smspusher/MainActivity.java")),
            StandardCharsets.UTF_8
        );
        String stateSource = new String(
            Files.readAllBytes(Paths.get("src/main/java/com/hippo2cat/smspusher/ui/MessageListUiState.java")),
            StandardCharsets.UTF_8
        );
        String combinedSource = source + stateSource;
        String messagesScreen = source
            .split("private void renderMessagesScreen\\(String macBaseUrl\\)", 2)[1]
            .split("private void renderMessageSyncStatusScreen", 2)[0];
        String messageStatusCard = source
            .split("private void renderMessageSyncStatusScreen\\(MessageListUiState state, String macBaseUrl\\)", 2)[1]
            .split("private void renderMessageFailureDetails", 2)[0];

        assertTrue(source.contains("VIEW_PENDING_MESSAGES"));
        assertTrue(source.contains("renderMessageSyncStatusScreen(state, macBaseUrl);"));
        assertTrue(source.contains("private void renderPendingMessagesScreen(String macBaseUrl)"));
        assertTrue(messageStatusCard.contains("card.setPadding(ConsoleTheme.dp(content, 24), ConsoleTheme.dp(content, 32), ConsoleTheme.dp(content, 24), ConsoleTheme.dp(content, 24));"));
        assertTrue(messageStatusCard.contains("new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)"));
        assertFalse(messageStatusCard.contains("ConsoleTheme.dp(content, hasPending ? 350 : 300)"));
        assertTrue(combinedSource.contains("R.string.android_message_all_forwarded"));
        assertTrue(combinedSource.contains("R.string.android_message_failed"));
        assertTrue(combinedSource.contains("R.string.android_message_error_details"));
        assertTrue(combinedSource.contains("R.string.android_message_pending_list_count"));
        assertTrue(source.contains("R.string.android_connection_status"));
        assertTrue(source.contains("R.string.android_connection_disconnected"));
        assertTrue(combinedSource.contains("R.string.android_message_view_messages"));
        assertTrue(source.contains("activeTab = VIEW_PENDING_MESSAGES;"));
        assertFalse(messagesScreen.contains("renderPendingMessageCard"));
        assertFalse(messagesScreen.contains("renderMessagesBottomAction"));
        assertFalse(source.contains("private void renderMessagesBottomAction"));
    }

    @Test
    public void permissionCenterMatchesFigmaWarningAndSinglePermissionCard() throws Exception {
        String source = new String(
            Files.readAllBytes(Paths.get("src/main/java/com/hippo2cat/smspusher/MainActivity.java")),
            StandardCharsets.UTF_8
        );
        String permissionScreen = source
            .split("private void renderPermissionTab\\(HomeUiState state\\)", 2)[1]
            .split("private void addPermissionToggleRow", 2)[0];
        String permissionToggleRow = source
            .split("private void addPermissionToggleRow\\(LinearLayout rows, int iconRes, HomeUiState.PermissionRow row, View.OnClickListener action\\)", 2)[1]
            .split("private void updateStatusFromStoredPairing", 2)[0];
        String permissionServiceRow = source
            .split("private void addPermissionServiceRow\\(LinearLayout rows, boolean needsRepair\\)", 2)[1]
            .split("private void addLanguageSettingsRow", 2)[0];

        assertTrue(permissionScreen.contains("R.string.android_permission_background_paused_title"));
        assertTrue(permissionScreen.contains("R.string.android_permission_background_paused_description"));
        assertTrue(permissionScreen.contains("R.string.android_permission_fix_issue"));
        assertTrue(permissionScreen.contains("R.string.android_permission_system"));
        assertTrue(permissionScreen.contains("R.string.android_permission_foreground_service"));
        assertTrue(permissionScreen.contains("R.string.android_permission_repair"));
        assertTrue(source.contains("private TextView settingsRowTitle(String text, int sp, int style)"));
        assertTrue(permissionToggleRow.contains("settingsRowTitle(row.title, 17, Typeface.BOLD)"));
        assertTrue(permissionServiceRow.contains("settingsRowTitle(getString(R.string.android_permission_foreground_service), 17, Typeface.NORMAL)"));
        assertFalse(permissionToggleRow.contains("singleLine(ConsoleTheme.label(content, row.title"));
        assertFalse(permissionServiceRow.contains("singleLine(ConsoleTheme.label(content, getString(R.string.android_permission_foreground_service)"));
        assertFalse(permissionScreen.contains("\"电池策略\""));
        assertFalse(permissionScreen.contains("\"MIUI 后台运行\""));
    }

    @Test
    public void settingsTabIncludesLanguageSelector() throws Exception {
        String source = new String(
            Files.readAllBytes(Paths.get("src/main/java/com/hippo2cat/smspusher/MainActivity.java")),
            StandardCharsets.UTF_8
        );
        String languageRow = source
            .split("private void addLanguageSettingsRow\\(LinearLayout rows\\)", 2)[1]
            .split("private String languagePreferenceLabel", 2)[0];
        String languageChevron = source
            .split("private TextView settingsLanguageChevron\\(\\)", 2)[1]
            .split("private String languagePreferenceLabel", 2)[0];

        assertTrue(source.contains("private void showLanguagePicker()"));
        assertTrue(source.contains("LanguagePreferenceStore.set(this, preference);"));
        assertTrue(source.contains("recreate();"));
        assertTrue(source.contains("R.string.common_language_title"));
        assertTrue(source.contains("R.string.common_language_auto"));
        assertTrue(source.contains("R.string.common_language_zh_cn"));
        assertTrue(source.contains("R.string.common_language_en_us"));
        assertTrue(languageRow.contains("settingsLanguageChevron()"));
        assertFalse(languageRow.contains("statusPill(\"›\""));
        assertFalse(languageChevron.contains("setBackground"));
        assertFalse(languageChevron.contains("statusPill"));
        assertFalse(languageChevron.contains("statusDot"));
    }

    @Test
    public void uiMotionHelperDefinesSubtleNativeAnimations() throws Exception {
        Path motionPath = Paths.get("src/main/java/com/hippo2cat/smspusher/ui/Motion.java");
        assertTrue("Motion helper should centralize UI animation timing", Files.exists(motionPath));
        String motion = new String(Files.readAllBytes(motionPath), StandardCharsets.UTF_8);

        assertTrue(motion.contains("SCREEN_ENTER_MS"));
        assertTrue(motion.contains("PRESS_MS"));
        assertTrue(motion.contains("ERROR_SHAKE_MS"));
        assertTrue(motion.contains("PULSE_MS"));
        assertTrue(motion.contains("BREATHE_MS"));
        assertTrue(motion.contains("private static boolean animationsEnabled(View view)"));
        assertTrue(motion.contains("public static void fadeInUp(View view)"));
        assertTrue(motion.contains("public static void staggerFadeInUp(View view, int index)"));
        assertTrue(motion.contains("public static void applyPressScale(View view)"));
        assertTrue(motion.contains("public static void pulse(View view)"));
        assertTrue(motion.contains("public static void breathe(View view)"));
        assertTrue(motion.contains("public static void shake(View view)"));
        assertTrue(motion.contains("public static void focusPop(View view)"));
        assertFalse(motion.contains("Lottie"));
    }

    @Test
    public void mainActivityAppliesMotionToKeyUiFlows() throws Exception {
        String source = new String(
            Files.readAllBytes(Paths.get("src/main/java/com/hippo2cat/smspusher/MainActivity.java")),
            StandardCharsets.UTF_8
        );
        String renderHome = source
            .split("private void renderHome\\(\\)", 2)[1]
            .split("@Override\\n    public void onBackPressed", 2)[0];
        String deviceSelection = source
            .split("private void renderDeviceSelectionScreen\\(\\)", 2)[1]
            .split("private LinearLayout deviceSelectionRow", 2)[0];
        String pairingCode = source
            .split("private void renderPairingCodeScreen\\(String baseUrl\\)", 2)[1]
            .split("private EditText pairingCodeInput", 2)[0];
        String pairInBackground = source
            .split("private void pairInBackground", 2)[1]
            .split("private void testConnection", 2)[0];
        String detailsBottomActions = source
            .split("private void renderDetailsBottomActions\\(\\)", 2)[1]
            .split("private TextView connectionTestFeedback", 2)[0];

        assertTrue(source.contains("import com.hippo2cat.smspusher.ui.Motion;"));
        assertTrue(renderHome.contains("Motion.fadeInUp(tabContent);"));
        assertTrue(deviceSelection.contains("searchIcon.setContentDescription(getString(R.string.android_discovery_rescan));"));
        assertTrue(deviceSelection.contains("searchIcon.setOnClickListener(v -> refreshDeviceDiscovery(searchIcon));"));
        assertTrue(deviceSelection.contains("Motion.applyPressScale(searchIcon);"));
        assertTrue(deviceSelection.contains("Motion.staggerFadeInUp(row, rendered);"));
        assertTrue(deviceSelection.contains("Motion.applyPressScale(manualTarget);"));
        assertTrue(source.contains("Motion.applyPressScale(row);"));
        assertTrue(source.contains("Motion.applyPressScale(item);"));
        assertTrue(source.contains("Motion.applyPressScale(button);"));
        assertTrue(pairingCode.contains("Motion.shake(inputRow);"));
        assertFalse(pairingCode.contains("Motion.focusPop(v);"));
        assertFalse(pairingCode.contains("input.setOnFocusChangeListener"));
        assertTrue(pairInBackground.contains("Motion.shake(codeRow);"));
        assertTrue(detailsBottomActions.contains("Motion.fadeInUp(feedback);"));
        assertTrue(source.contains("Motion.fadeInUp(error);"));
    }

    @Test
    public void deviceSelectionRefreshIconBreathesAndLetsDiscoveryCallbackRefresh() throws Exception {
        String source = new String(
            Files.readAllBytes(Paths.get("src/main/java/com/hippo2cat/smspusher/MainActivity.java")),
            StandardCharsets.UTF_8
        );
        String refresh = source
            .split("private void refreshDeviceDiscovery\\(View refreshIcon\\)", 2)[1]
            .split("private LinearLayout deviceSelectionRow", 2)[0];
        String restart = source
            .split("private void restartDiscovery\\(\\)", 2)[1]
            .split("private void startDiscovery", 2)[0];

        assertTrue(refresh.contains("Motion.breathe(refreshIcon);"));
        assertTrue(refresh.contains("discoveredServices = new ArrayList<>();"));
        assertTrue(refresh.contains("restartDiscovery();"));
        assertFalse(refresh.contains("postDelayed("));
        assertFalse(refresh.contains("renderHome();"));
        assertTrue(restart.contains("if (discovery != null) discovery.stop();"));
        assertTrue(restart.contains("startDiscovery();"));
    }

    @Test
    public void connectionDetailsScreenMatchesFigmaCardLayoutWithoutFalseSecurityClaims() throws Exception {
        String source = new String(
            Files.readAllBytes(Paths.get("src/main/java/com/hippo2cat/smspusher/MainActivity.java")),
            StandardCharsets.UTF_8
        );
        String detailsScreen = source
            .split("private void renderConnectionDetailsScreen\\(PairingCredential credential, String macBaseUrl\\)", 2)[1]
            .split("private void renderActivityScreen", 2)[0];
        String statusCard = source
            .split("private void renderDetailsStatusCard\\(ConnectionDetailsUiState state\\)", 2)[1]
            .split("private LinearLayout detailSection", 2)[0];
        String section = source
            .split("private LinearLayout detailSection\\(String title\\)", 2)[1]
            .split("private void addGroupedDetailRow", 2)[0];
        String row = source
            .split("private void addGroupedDetailRow\\(LinearLayout section, int iconRes, int iconColor, String title, String subtitle, String value, int valueColor, boolean chipValue\\)", 2)[1]
            .split("private void addGroupedDetailDivider", 2)[0];
        String bottomActions = source
            .split("private void renderDetailsBottomActions\\(\\)", 2)[1]
            .split("private TextView connectionTestFeedback", 2)[0];

        assertTrue(detailsScreen.contains("detailSection(getString(R.string.android_connection_network_config))"));
        assertTrue(detailsScreen.contains("detailSection(getString(R.string.android_connection_security))"));
        assertTrue(detailsScreen.contains("detailSection(getString(R.string.android_connection_technical_specs))"));
        assertTrue(detailsScreen.contains("secureAuthText(credential)"));
        assertTrue(detailsScreen.contains("appVersionName()"));
        assertTrue(detailsScreen.contains("\"v\" + appVersion"));
        assertTrue(detailsScreen.contains("state.securityText"));
        assertFalse(detailsScreen.contains("\"AES-256"));
        assertFalse(detailsScreen.contains("\"E2EE"));

        assertTrue(statusCard.contains("ConsoleTheme.dp(content, 215)"));
        assertTrue(statusCard.contains("ConsoleTheme.dp(content, 80), ConsoleTheme.dp(content, 80)"));
        assertTrue(statusCard.contains("detailStatusDot(statusColor, 7)"));
        assertTrue(statusCard.contains("Typeface.NORMAL"));

        assertTrue(section.contains("detailCard()"));
        assertTrue(section.contains("ConsoleTheme.dp(content, 22), ConsoleTheme.dp(content, 20), ConsoleTheme.dp(content, 16), ConsoleTheme.dp(content, 16)"));
        assertTrue(row.contains("ConsoleTheme.dp(content, 56)"));
        assertTrue(row.contains("detailValueChip(value == null || value.isEmpty() ? \"-\" : value, valueColor)"));
        assertTrue(source.contains("private LinearLayout detailCard()"));
        assertTrue(source.contains("private LinearLayout detailValueChip(String text, int color)"));
        assertTrue(source.contains("private String secureAuthText(PairingCredential credential)"));

        assertTrue(bottomActions.contains("ConsoleTheme.dp(content, 46)"));
        assertTrue(bottomActions.contains("actions.setPadding(ConsoleTheme.dp(content, 20), ConsoleTheme.dp(content, 16), ConsoleTheme.dp(content, 20), ConsoleTheme.dp(content, 16))"));
        assertTrue(source.contains("primary ? ConsoleTheme.ACCENT_TEAL : ConsoleTheme.colorWithAlpha(ConsoleTheme.STROKE, 0)"));
        assertFalse(bottomActions.contains("ConsoleTheme.divider(content)"));
    }

    @Test
    public void taskBottomSurfaceScreensKeepCompactGapAboveActions() throws Exception {
        String source = new String(
            Files.readAllBytes(Paths.get("src/main/java/com/hippo2cat/smspusher/MainActivity.java")),
            StandardCharsets.UTF_8
        );
        String renderHome = source
            .split("private void renderHome\\(\\)", 2)[1]
            .split("@Override\\n    public void onBackPressed", 2)[0];
        String detailsScreen = source
            .split("private void renderConnectionDetailsScreen\\(PairingCredential credential, String macBaseUrl\\)", 2)[1]
            .split("private void renderActivityScreen", 2)[0];

        assertTrue(source.contains("private boolean usesTaskBottomSurface()"));
        assertTrue(renderHome.contains("int pageBottomPadding = usesTaskBottomSurface() ? ConsoleTheme.dp(content, 12) : pagePadding;"));
        assertTrue(renderHome.contains("content.setPadding(pagePadding, pagePadding, pagePadding, pageBottomPadding);"));
        assertTrue(detailsScreen.contains("tabContent.setPadding(ConsoleTheme.dp(content, 4), 0, ConsoleTheme.dp(content, 4), 0);"));
        assertFalse(detailsScreen.contains("ConsoleTheme.dp(content, 88)"));
    }
}
