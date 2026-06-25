package com.hippo2cat.smspusher;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.hippo2cat.smspusher.auth.SecureTokenStore;
import com.hippo2cat.smspusher.auth.PairingCredential;
import com.hippo2cat.smspusher.delivery.DeliveryWorker;
import com.hippo2cat.smspusher.discovery.MacDiscoveryService;
import com.hippo2cat.smspusher.discovery.MacEndpointUrls;
import com.hippo2cat.smspusher.discovery.MacEndpointResolution;
import com.hippo2cat.smspusher.discovery.NsdMacEndpointResolver;
import com.hippo2cat.smspusher.i18n.AppLocale;
import com.hippo2cat.smspusher.i18n.LanguagePreference;
import com.hippo2cat.smspusher.i18n.LanguagePreferenceStore;
import com.hippo2cat.smspusher.logging.DiagnosticLogExporter;
import com.hippo2cat.smspusher.miui.MiuiPermissionRequester;
import com.hippo2cat.smspusher.net.SmsBridgeClient;
import com.hippo2cat.smspusher.net.UrlConnectionTransport;
import com.hippo2cat.smspusher.permission.RuntimePermissionRequestPolicy;
import com.hippo2cat.smspusher.state.ForwardingStatsStore;
import com.hippo2cat.smspusher.state.ForwardingStatsText;
import com.hippo2cat.smspusher.state.PairingEndpoint;
import com.hippo2cat.smspusher.state.PairingStatusText;
import com.hippo2cat.smspusher.state.PairingStore;
import com.hippo2cat.smspusher.state.ServiceHealthStore;
import com.hippo2cat.smspusher.state.ClientIdentityStore;
import com.hippo2cat.smspusher.ui.ConnectionConsoleUiState;
import com.hippo2cat.smspusher.ui.ConnectionDetailsUiState;
import com.hippo2cat.smspusher.ui.ConnectionTestUiState;
import com.hippo2cat.smspusher.ui.ConsoleTheme;
import com.hippo2cat.smspusher.ui.DeviceTopologyConnectorMotion;
import com.hippo2cat.smspusher.ui.DeviceTopologySizing;
import com.hippo2cat.smspusher.ui.HomeUiState;
import com.hippo2cat.smspusher.ui.MessageActivityUiState;
import com.hippo2cat.smspusher.ui.MessageListUiState;
import com.hippo2cat.smspusher.ui.Motion;
import com.hippo2cat.smspusher.ui.PairingFeedback;
import com.hippo2cat.smspusher.ui.PermissionToggleUiState;
import com.hippo2cat.smspusher.sms.MessageEventStore;
import com.hippo2cat.smspusher.sms.MessageEvent;
import com.hippo2cat.smspusher.sms.PendingMessage;
import com.hippo2cat.smspusher.update.AndroidUpdateChecker;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("deprecation")
public final class MainActivity extends Activity {
    private static final int RUNTIME_PERMISSION_REQUEST = 10;
    private static final int TAB_MESSAGES = 0;
    private static final int TAB_DEVICES = 1;
    private static final int TAB_SETTINGS = 2;
    private static final int VIEW_CONNECTION_DETAILS = 10;
    private static final int VIEW_ACTIVITY = 11;
    private static final int VIEW_MANUAL_PAIRING = 12;
    private static final int VIEW_MESSAGE_DETAIL = 13;
    private static final int VIEW_DEVICE_SELECTION = 14;
    private static final int VIEW_PENDING_MESSAGES = 15;
    private static final Logger LOG = LoggerFactory.getLogger("SmsBridge");
    private static final AtomicBoolean pairingVerificationInFlight = new AtomicBoolean(false);
    private static final long NETWORK_AVAILABLE_VERIFY_SUPPRESS_MS = 2_000L;
    private static final long CONNECTION_TEST_MIN_VISIBLE_MS = 600L;
    private TextView status;
    private TextView sectionError;
    private LinearLayout content;
    private LinearLayout tabContent;
    private LinearLayout bottomNavHost;
    private ScrollView scrollView;
    private LinearLayout macList;
    private int activeTab = TAB_DEVICES;
    private int systemBottomInset;
    private boolean currentWhiteFlowVisible;
    private boolean listenerRunning;
    private long lastPairingVerifiedAtMillis;
    private ConnectionTestUiState connectionTestUiState;
    private List<NsdServiceInfo> discoveredServices = new ArrayList<>();
    private MessageListUiState.Row selectedMessageDetail;
    private String pairingBaseUrl = "";
    private String pairingServiceName = "";
    private MacDiscoveryService discovery;
    private BroadcastReceiver connectionStatusRefreshReceiver;
    private boolean connectionStatusRefreshRenderRequested;
    private boolean endpointRecoveryInFlight;
    private final Runnable connectionStatusRefreshTask = new Runnable() {
        @Override
        public void run() {
            refreshConnectionStatusNow();
        }
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppLocale.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AndroidUpdateChecker.start(this);
        configureSystemBars();
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(ConsoleTheme.BACKGROUND);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(ConsoleTheme.BACKGROUND);
        scrollView = scroll;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ConsoleTheme.BACKGROUND);
        bottomNavHost = new LinearLayout(this);
        bottomNavHost.setOrientation(LinearLayout.VERTICAL);
        bottomNavHost.setBackgroundColor(ConsoleTheme.BACKGROUND);
        applySafeAreaPadding(scroll, root, bottomNavHost);
        scroll.addView(root, new ScrollView.LayoutParams(
            ScrollView.LayoutParams.MATCH_PARENT,
            ScrollView.LayoutParams.WRAP_CONTENT
        ));
        screen.addView(scroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1
        ));
        screen.addView(bottomNavHost, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        content = root;
        status = new TextView(this);
        status.setTextSize(20);
        sectionError = new TextView(this);
        sectionError.setTextSize(13);
        sectionError.setTextColor(ConsoleTheme.ACCENT_RED);
        sectionError.setVisibility(View.GONE);
        macList = new LinearLayout(this);
        macList.setOrientation(LinearLayout.VERTICAL);
        tabContent = new LinearLayout(this);
        tabContent.setOrientation(LinearLayout.VERTICAL);
        tabContent.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        setContentView(screen, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        connectionTestUiState = ConnectionTestUiState.idle(connectionTestCopy());
        registerConnectionStatusRefreshReceiver();
        scroll.requestApplyInsets();
        renderHome();
        requestRequiredPermissionsIfNeeded();
        startDiscovery();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshPermissionStateFromSystem();
    }

    private void registerConnectionStatusRefreshReceiver() {
        if (connectionStatusRefreshReceiver != null) return;
        connectionStatusRefreshReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || !ConnectionStatusEvents.ACTION_REFRESH.equals(intent.getAction())) return;
                String source = intent.getStringExtra(ConnectionStatusEvents.EXTRA_SOURCE);
                source = source == null ? "" : source;
                refreshConnectionStatusFromEvent(source);
            }
        };
        IntentFilter filter = new IntentFilter(ConnectionStatusEvents.ACTION_REFRESH);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(connectionStatusRefreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(connectionStatusRefreshReceiver, filter);
        }
    }

    private void unregisterConnectionStatusRefreshReceiver() {
        if (content != null) content.removeCallbacks(connectionStatusRefreshTask);
        connectionStatusRefreshRenderRequested = false;
        if (connectionStatusRefreshReceiver == null) return;
        try {
            unregisterReceiver(connectionStatusRefreshReceiver);
        } catch (IllegalArgumentException ignored) {
            // The receiver can already be unregistered during Activity teardown.
        } finally {
            connectionStatusRefreshReceiver = null;
        }
    }

    private void refreshConnectionStatus() {
        scheduleConnectionStatusRefresh(true);
    }

    private void refreshConnectionStatusFromEvent(String source) {
        LOG.info(
            "connection status refresh event source={} verificationInFlight={} endpointRecoveryInFlight={}",
            source,
            pairingVerificationInFlight.get(),
            endpointRecoveryInFlight
        );
        if ("network_available".equals(source)) {
            if (shouldDeferNetworkAvailablePairingVerification()) {
                LOG.info(
                    "network available verification deferred verificationInFlight={} endpointRecoveryInFlight={} recentlyVerified={}",
                    pairingVerificationInFlight.get(),
                    endpointRecoveryInFlight,
                    wasPairingVerifiedRecently()
                );
                scheduleConnectionStatusRefresh(shouldRenderConnectionStatusRefresh());
                return;
            }
            updateStatusFromStoredPairing();
            return;
        }
        scheduleConnectionStatusRefresh(shouldRenderConnectionStatusRefresh());
    }

    private boolean shouldDeferNetworkAvailablePairingVerification() {
        return pairingVerificationInFlight.get()
            || endpointRecoveryInFlight
            || wasPairingVerifiedRecently();
    }

    private boolean wasPairingVerifiedRecently() {
        if (lastPairingVerifiedAtMillis <= 0L) return false;
        return SystemClock.elapsedRealtime() - lastPairingVerifiedAtMillis < NETWORK_AVAILABLE_VERIFY_SUPPRESS_MS;
    }

    private void scheduleConnectionStatusRefresh(boolean renderRequested) {
        if (content == null) return;
        content.removeCallbacks(connectionStatusRefreshTask);
        connectionStatusRefreshRenderRequested = connectionStatusRefreshRenderRequested || renderRequested;
        content.post(connectionStatusRefreshTask);
    }

    private void refreshConnectionStatusNow() {
        if (content == null) return;
        listenerRunning = isConnectionHealthy();
        boolean renderRequested = connectionStatusRefreshRenderRequested;
        connectionStatusRefreshRenderRequested = false;
        if (renderRequested) {
            renderHome();
        }
    }

    private boolean shouldRenderConnectionStatusRefresh() {
        return pairingBaseUrl.isEmpty()
            && activeTab != VIEW_MANUAL_PAIRING
            && activeTab != VIEW_DEVICE_SELECTION;
    }

    private boolean isConnectionHealthy() {
        return ServiceHealthStore.isHealthy(this) && ServiceHealthStore.isNetworkAvailable(this);
    }

    private void refreshPermissionStateFromSystem() {
        if (content == null) return;
        if (hasReceiveSmsPermission() && hasReadSmsPermission() && hasNotificationPermission()) {
            updateStatusFromStoredPairing();
        } else {
            status.setText(permissionStatus(missingRuntimePermissions()));
            renderHome();
        }
    }

    private void renderHome() {
        content.removeAllViews();
        bottomNavHost.removeAllViews();
        bottomNavHost.setBackgroundColor(ConsoleTheme.BACKGROUND);
        boolean pairingCodeVisible = isPairingCodeVisible();
        boolean whiteFlowVisible = pairingCodeVisible || activeTab == VIEW_MANUAL_PAIRING || activeTab == VIEW_DEVICE_SELECTION;
        currentWhiteFlowVisible = whiteFlowVisible;
        int pagePadding = ConsoleTheme.dp(content, whiteFlowVisible ? 0 : 16);
        int pageBottomPadding = usesTaskBottomSurface() ? ConsoleTheme.dp(content, 12) : pagePadding;
        content.setPadding(pagePadding, pagePadding, pagePadding, pageBottomPadding);
        content.setBackgroundColor(whiteFlowVisible ? Color.WHITE : ConsoleTheme.BACKGROUND);
        if (scrollView != null) scrollView.setBackgroundColor(whiteFlowVisible ? Color.WHITE : ConsoleTheme.BACKGROUND);
        bottomNavHost.setBackgroundColor(bottomHostBackgroundColor(whiteFlowVisible));
        bottomNavHost.setPadding(
            0,
            0,
            0,
            bottomHostBottomPadding(whiteFlowVisible)
        );
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(whiteFlowVisible ? Color.WHITE : ConsoleTheme.BACKGROUND);
            getWindow().setNavigationBarColor(bottomNavigationBarColor(whiteFlowVisible));
        }
        PairingCredential credential = new SecureTokenStore(this).loadCredential();
        String macBaseUrl = PairingStore.loadMacBaseUrl(this);
        boolean paired = credential != null && !credential.requiresSecureRePairing() && !macBaseUrl.isEmpty();
        listenerRunning = isConnectionHealthy();
        content.addView(sectionError);
        tabContent.removeAllViews();
        tabContent.setBackgroundColor(whiteFlowVisible ? Color.WHITE : ConsoleTheme.BACKGROUND);
        tabContent.setPadding(0, 0, 0, 0);
        content.addView(tabContent);
        if (activeTab == TAB_MESSAGES) {
            renderMessagesScreen(macBaseUrl);
        } else if (activeTab == VIEW_MESSAGE_DETAIL) {
            renderMessageDetailScreen();
        } else if (activeTab == VIEW_PENDING_MESSAGES) {
            renderPendingMessagesScreen(macBaseUrl);
        } else if (activeTab == TAB_SETTINGS) {
            renderSettingsScreen();
        } else if (activeTab == VIEW_CONNECTION_DETAILS) {
            renderConnectionDetailsScreen(credential, macBaseUrl);
        } else if (activeTab == VIEW_ACTIVITY) {
            renderActivityScreen();
        } else if (activeTab == VIEW_DEVICE_SELECTION) {
            renderDeviceSelectionScreen();
        } else if (activeTab == VIEW_MANUAL_PAIRING) {
            renderManualPairingScreen();
        } else {
            renderDeviceScreen(paired, macBaseUrl, credential);
        }
        Motion.fadeInUp(tabContent);
        if (showsBottomNavigation()) renderBottomNavigation();
    }

    @Override
    public void onBackPressed() {
        if (activeTab == TAB_DEVICES && !pairingBaseUrl.isEmpty()) {
            pairingBaseUrl = "";
            pairingServiceName = "";
            refreshConnectionStatus();
            return;
        }
        if (activeTab == VIEW_MESSAGE_DETAIL) {
            selectedMessageDetail = null;
            activeTab = TAB_MESSAGES;
            refreshConnectionStatus();
            return;
        }
        if (activeTab == VIEW_PENDING_MESSAGES) {
            activeTab = TAB_MESSAGES;
            refreshConnectionStatus();
            return;
        }
        if (activeTab == VIEW_MANUAL_PAIRING) {
            activeTab = VIEW_DEVICE_SELECTION;
            refreshConnectionStatus();
            return;
        }
        if (activeTab == TAB_MESSAGES || activeTab == VIEW_CONNECTION_DETAILS || activeTab == VIEW_ACTIVITY || activeTab == VIEW_DEVICE_SELECTION) {
            activeTab = TAB_DEVICES;
            refreshConnectionStatus();
            return;
        }
        super.onBackPressed();
    }

    private void renderMessagesScreen(String macBaseUrl) {
        List<PendingMessage> pending = DeliveryWorker.pendingMessages(this, 100);
        MessageListUiState state = MessageListUiState.from(pending, macBaseUrl != null && !macBaseUrl.isEmpty(), messageListCopy());
        renderMessageSyncStatusScreen(state, macBaseUrl);
    }

    private void renderMessageSyncStatusScreen(MessageListUiState state, String macBaseUrl) {
        boolean hasPending = state.viewMessagesVisible;
        boolean failed = state.failureVisible;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(ConsoleTheme.dp(content, 24), ConsoleTheme.dp(content, 32), ConsoleTheme.dp(content, 24), ConsoleTheme.dp(content, 24));
        card.setBackground(ConsoleTheme.rounded(content, ConsoleTheme.SURFACE, 20, ConsoleTheme.colorWithAlpha(Color.rgb(193, 198, 215), 76), 1));
        card.setElevation(ConsoleTheme.dp(content, 10));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, ConsoleTheme.dp(content, 8), 0, 0);
        tabContent.addView(card, cardParams);

        FrameLayout iconShell = new FrameLayout(this);
        int accent = failed ? Color.rgb(204, 38, 38) : ConsoleTheme.ACCENT_TEAL_DARK;
        iconShell.setBackground(ConsoleTheme.rounded(content, ConsoleTheme.colorWithAlpha(accent, failed ? 20 : 26), 99, ConsoleTheme.colorWithAlpha(accent, 0), 0));
        iconShell.addView(iconView(hasPending || failed ? R.drawable.ic_bridge_refresh : R.drawable.ic_bridge_check_circle, accent, hasPending || failed ? 34 : 44), new FrameLayout.LayoutParams(
            ConsoleTheme.dp(content, hasPending || failed ? 34 : 44),
            ConsoleTheme.dp(content, hasPending || failed ? 34 : 44),
            Gravity.CENTER
        ));
        View liveDot = new View(this);
        liveDot.setBackground(ConsoleTheme.rounded(content, failed ? Color.rgb(204, 38, 38) : ConsoleTheme.ACCENT_LIME, 99, Color.WHITE, 2));
        FrameLayout.LayoutParams dotParams = new FrameLayout.LayoutParams(ConsoleTheme.dp(content, 12), ConsoleTheme.dp(content, 12), Gravity.TOP | Gravity.RIGHT);
        dotParams.setMargins(0, ConsoleTheme.dp(content, 8), ConsoleTheme.dp(content, 8), 0);
        iconShell.addView(liveDot, dotParams);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(ConsoleTheme.dp(content, 96), ConsoleTheme.dp(content, 96));
        iconParams.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(iconShell, iconParams);

        TextView title = ConsoleTheme.label(content, state.title, 24, Color.rgb(26, 27, 31), Typeface.NORMAL);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(0, ConsoleTheme.dp(content, 28), 0, 0);
        card.addView(title, titleParams);

        Button push = messagePrimaryButton(state.primaryActionLabel, state.retryEnabled);
        push.setOnClickListener(v -> {
            if (macBaseUrl == null || macBaseUrl.isEmpty()) {
                showStatusToast(getString(R.string.android_toast_not_connected_mac));
                return;
            }
            DeliveryWorker.drainAsync(this, macBaseUrl);
            showStatusToast(getString(R.string.android_toast_pushing));
        });
        LinearLayout.LayoutParams pushParams = new LinearLayout.LayoutParams(ConsoleTheme.dp(content, 120), ConsoleTheme.dp(content, 42));
        pushParams.gravity = Gravity.CENTER_HORIZONTAL;
        pushParams.setMargins(0, ConsoleTheme.dp(content, 32), 0, 0);
        card.addView(push, pushParams);

        if (state.viewMessagesVisible) {
            TextView viewMessages = ConsoleTheme.label(content, state.viewMessagesLabel, 13, ConsoleTheme.ACCENT_TEAL_DARK, Typeface.BOLD);
            viewMessages.setGravity(Gravity.CENTER);
            viewMessages.setPadding(ConsoleTheme.dp(content, 16), ConsoleTheme.dp(content, 8), ConsoleTheme.dp(content, 16), ConsoleTheme.dp(content, 8));
            viewMessages.setOnClickListener(v -> {
                activeTab = VIEW_PENDING_MESSAGES;
                refreshConnectionStatus();
            });
            LinearLayout.LayoutParams viewParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            viewParams.gravity = Gravity.CENTER_HORIZONTAL;
            viewParams.setMargins(0, ConsoleTheme.dp(content, 16), 0, 0);
            card.addView(viewMessages, viewParams);
        }
        if (state.failureVisible) {
            renderMessageFailureDetails(state);
        }
    }

    private void renderMessageFailureDetails(MessageListUiState state) {
        LinearLayout panel = ConsoleTheme.roundedPanel(content, ConsoleTheme.SURFACE, ConsoleTheme.colorWithAlpha(ConsoleTheme.ACCENT_RED, 45));
        panel.setPadding(ConsoleTheme.dp(content, 16), ConsoleTheme.dp(content, 16), ConsoleTheme.dp(content, 16), ConsoleTheme.dp(content, 16));

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setGravity(Gravity.TOP);
        inner.setPadding(ConsoleTheme.dp(content, 14), ConsoleTheme.dp(content, 14), ConsoleTheme.dp(content, 14), ConsoleTheme.dp(content, 14));
        inner.setBackground(ConsoleTheme.rounded(content, ConsoleTheme.colorWithAlpha(ConsoleTheme.ACCENT_RED, 18), 8, ConsoleTheme.colorWithAlpha(ConsoleTheme.ACCENT_RED, 0), 0));
        inner.addView(iconView(R.drawable.ic_bridge_pulse, ConsoleTheme.ACCENT_RED, 22), new LinearLayout.LayoutParams(ConsoleTheme.dp(content, 28), ConsoleTheme.dp(content, 28)));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        textParams.setMargins(ConsoleTheme.dp(content, 12), 0, 0, 0);
        text.addView(ConsoleTheme.label(content, state.failureTitle, 15, ConsoleTheme.ACCENT_RED, Typeface.BOLD));
        TextView detail = ConsoleTheme.label(content, state.failureDetail, 14, Color.rgb(65, 71, 85), Typeface.NORMAL);
        detail.setSingleLine(false);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailParams.setMargins(0, ConsoleTheme.dp(content, 6), 0, 0);
        text.addView(detail, detailParams);
        inner.addView(text, textParams);
        panel.addView(inner);
        tabContent.addView(panel);
    }

    private Button messagePrimaryButton(String text, boolean enabled) {
        Button button = ConsoleTheme.roundedButton(content, text, true);
        button.setTextSize(13);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.55f);
        button.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_bridge_refresh, 0, 0, 0);
        button.setCompoundDrawableTintList(ColorStateList.valueOf(Color.WHITE));
        button.setCompoundDrawablePadding(ConsoleTheme.dp(content, 8));
        return button;
    }

    private void renderPendingMessagesScreen(String macBaseUrl) {
        MessageListUiState state = MessageListUiState.from(DeliveryWorker.pendingMessages(this, 100), macBaseUrl != null && !macBaseUrl.isEmpty(), messageListCopy());
        TextView title = ConsoleTheme.label(content, state.pendingListTitle, 15, Color.rgb(65, 71, 85), Typeface.NORMAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(ConsoleTheme.dp(content, 4), ConsoleTheme.dp(content, 14), 0, ConsoleTheme.dp(content, 8));
        tabContent.addView(title, titleParams);

        if (state.rows.isEmpty()) {
            LinearLayout empty = ConsoleTheme.panel(content);
            empty.setGravity(Gravity.CENTER_HORIZONTAL);
            empty.addView(iconView(R.drawable.ic_bridge_check_circle, ConsoleTheme.ACCENT_LIME, 40));
            TextView emptyTitle = ConsoleTheme.label(content, state.emptyTitle, 20, Color.rgb(26, 27, 31), Typeface.BOLD);
            LinearLayout.LayoutParams emptyTitleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            emptyTitleParams.setMargins(0, ConsoleTheme.dp(content, 12), 0, 0);
            empty.addView(emptyTitle, emptyTitleParams);
            tabContent.addView(empty);
        } else {
            for (MessageListUiState.Row row : state.rows) {
                renderPendingMessageCard(row);
            }
        }
        renderPendingMessagesBottomAction(macBaseUrl, state);
    }

    private void renderPendingMessagesBottomAction(String macBaseUrl, MessageListUiState state) {
        prepareTaskBottomSurface();
        LinearLayout actionBar = new LinearLayout(this);
        actionBar.setOrientation(LinearLayout.HORIZONTAL);
        actionBar.setGravity(Gravity.CENTER_VERTICAL);
        actionBar.setPadding(ConsoleTheme.dp(content, 20), ConsoleTheme.dp(content, 14), ConsoleTheme.dp(content, 20), ConsoleTheme.dp(content, 14));

        LinearLayout statusBlock = new LinearLayout(this);
        statusBlock.setOrientation(LinearLayout.VERTICAL);
        statusBlock.addView(ConsoleTheme.label(content, getString(R.string.android_connection_status), 12, ConsoleTheme.TEXT_SECONDARY, Typeface.NORMAL));
        LinearLayout statusLine = new LinearLayout(this);
        statusLine.setGravity(Gravity.CENTER_VERTICAL);
        boolean paired = macBaseUrl != null && !macBaseUrl.isEmpty();
        statusLine.addView(statusDot(paired ? ConsoleTheme.ACCENT_LIME : ConsoleTheme.TEXT_MUTED, 7));
        TextView value = ConsoleTheme.label(content, paired ? getString(R.string.android_connection_connected) : getString(R.string.android_connection_disconnected), 15, Color.rgb(26, 27, 31), Typeface.NORMAL);
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        valueParams.setMargins(ConsoleTheme.dp(content, 6), 0, 0, 0);
        statusLine.addView(value, valueParams);
        statusBlock.addView(statusLine);
        actionBar.addView(statusBlock, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        LinearLayout retry = iconActionButton(state.primaryActionLabel, R.drawable.ic_bridge_refresh, true, state.retryEnabled, v -> {
            if (macBaseUrl == null || macBaseUrl.isEmpty()) {
                showStatusToast(getString(R.string.android_toast_not_connected_mac));
                return;
            }
            DeliveryWorker.drainAsync(this, macBaseUrl);
            showStatusToast(getString(R.string.android_toast_pushing));
        });
        actionBar.addView(retry, new LinearLayout.LayoutParams(ConsoleTheme.dp(content, 142), ConsoleTheme.dp(content, 56)));
        bottomNavHost.addView(actionBar);
    }

    private void renderMessageDetailScreen() {
        MessageListUiState.Row row = selectedMessageDetail;
        if (row == null) {
            tabContent.addView(emptyMessageDetail());
            return;
        }

        TextView back = ConsoleTheme.label(content, getString(R.string.android_message_back_messages), 16, ConsoleTheme.ACCENT_TEAL_DARK, Typeface.BOLD);
        back.setPadding(0, ConsoleTheme.dp(content, 4), 0, ConsoleTheme.dp(content, 12));
        back.setOnClickListener(v -> {
            selectedMessageDetail = null;
            activeTab = TAB_MESSAGES;
            refreshConnectionStatus();
        });
        tabContent.addView(back);

        TextView title = ConsoleTheme.label(content, getString(R.string.android_message_detail_title), 28, Color.rgb(26, 27, 31), Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(0, ConsoleTheme.dp(content, 4), 0, ConsoleTheme.dp(content, 18));
        tabContent.addView(title, titleParams);

        LinearLayout statusCard = ConsoleTheme.roundedPanel(content, ConsoleTheme.SURFACE, ConsoleTheme.STROKE);
        statusCard.setPadding(ConsoleTheme.dp(content, 18), ConsoleTheme.dp(content, 18), ConsoleTheme.dp(content, 18), ConsoleTheme.dp(content, 18));
        LinearLayout statusHeader = new LinearLayout(this);
        statusHeader.setOrientation(LinearLayout.HORIZONTAL);
        statusHeader.setGravity(Gravity.CENTER_VERTICAL);
        statusHeader.addView(iconBadge(R.drawable.ic_bridge_sms, messageStatusColor(row.status), 48));
        LinearLayout statusText = new LinearLayout(this);
        statusText.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams statusTextParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        statusTextParams.setMargins(ConsoleTheme.dp(content, 14), 0, 0, 0);
        statusText.addView(ConsoleTheme.label(content, getString(R.string.android_message_status_label), 13, ConsoleTheme.TEXT_SECONDARY, Typeface.BOLD));
        TextView status = ConsoleTheme.label(content, row.statusText, 22, Color.rgb(26, 27, 31), Typeface.BOLD);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.setMargins(0, ConsoleTheme.dp(content, 6), 0, 0);
        statusText.addView(status, statusParams);
        statusHeader.addView(statusText, statusTextParams);
        statusCard.addView(statusHeader);
        tabContent.addView(statusCard);

        LinearLayout meta = ConsoleTheme.roundedPanel(content, ConsoleTheme.SURFACE, ConsoleTheme.STROKE);
        meta.setPadding(0, 0, 0, 0);
        addMessageDetailRow(meta, getString(R.string.android_message_sender), row.sender);
        meta.addView(ConsoleTheme.divider(content));
        addMessageDetailRow(meta, getString(R.string.android_message_received_at), messageTimeText(row.receivedAt));
        meta.addView(ConsoleTheme.divider(content));
        addMessageDetailRow(meta, getString(R.string.android_message_message_id), shortMessageId(row.messageId));
        tabContent.addView(meta);

        LinearLayout body = ConsoleTheme.roundedPanel(content, ConsoleTheme.SURFACE, ConsoleTheme.STROKE);
        body.addView(ConsoleTheme.label(content, getString(R.string.android_message_body), 16, Color.rgb(26, 27, 31), Typeface.BOLD));
        TextView bodyText = ConsoleTheme.label(content, row.bodyPreview, 17, ConsoleTheme.TEXT_SECONDARY, Typeface.NORMAL);
        bodyText.setSingleLine(false);
        LinearLayout.LayoutParams bodyTextParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyTextParams.setMargins(0, ConsoleTheme.dp(content, 14), 0, 0);
        body.addView(bodyText, bodyTextParams);
        tabContent.addView(body);

        if (row.failureReason != null && !row.failureReason.isEmpty()) {
            LinearLayout failure = ConsoleTheme.roundedPanel(content, ConsoleTheme.SURFACE, ConsoleTheme.ACCENT_RED);
            failure.addView(ConsoleTheme.label(content, getString(R.string.android_message_failure_reason), 16, ConsoleTheme.ACCENT_RED, Typeface.BOLD));
            TextView reason = ConsoleTheme.label(content, row.failureReason, 14, ConsoleTheme.TEXT_SECONDARY, Typeface.NORMAL);
            reason.setSingleLine(false);
            LinearLayout.LayoutParams reasonParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            reasonParams.setMargins(0, ConsoleTheme.dp(content, 10), 0, 0);
            failure.addView(reason, reasonParams);
            tabContent.addView(failure);
        }
    }

    private LinearLayout emptyMessageDetail() {
        LinearLayout empty = ConsoleTheme.panel(content);
        empty.setGravity(Gravity.CENTER_HORIZONTAL);
        empty.addView(iconView(R.drawable.ic_bridge_sms, ConsoleTheme.TEXT_MUTED, 44));
        TextView title = ConsoleTheme.label(content, getString(R.string.android_message_detail_title), 22, ConsoleTheme.TEXT_PRIMARY, Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(0, ConsoleTheme.dp(content, 14), 0, 0);
        empty.addView(title, titleParams);
        TextView detail = ConsoleTheme.label(content, getString(R.string.android_message_detail_updated), 14, ConsoleTheme.TEXT_SECONDARY, Typeface.NORMAL);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailParams.setMargins(0, ConsoleTheme.dp(content, 8), 0, 0);
        empty.addView(detail, detailParams);
        return empty;
    }

    private void renderDeviceScreen(boolean paired, String macBaseUrl, PairingCredential credential) {
        tabContent.setPadding(ConsoleTheme.dp(content, 12), 0, ConsoleTheme.dp(content, 12), 0);
        if (!paired && !pairingBaseUrl.isEmpty()) {
            renderPairingCodeScreen(pairingBaseUrl);
            return;
        }
        boolean permissionsOk = hasReceiveSmsPermission() && hasReadSmsPermission() && hasNotificationPermission();
        renderDeviceStatusCard(paired, permissionsOk, macBaseUrl);
        if (paired) renderHomeSecondaryActions();
    }

    private void renderHomeSecondaryActions() {
        LinearLayout details = deviceActionRow(R.drawable.ic_bridge_monitor, getString(R.string.android_home_connection_details), getString(R.string.android_home_view_technical_status));
        details.setOnClickListener(v -> {
            activeTab = VIEW_CONNECTION_DETAILS;
            refreshConnectionStatus();
        });
        addHomeSecondaryAction(details, 23);

        LinearLayout activity = deviceActionRow(R.drawable.ic_bridge_list, getString(R.string.android_home_recent_activity), recentActivitySubtitle());
        activity.setOnClickListener(v -> {
            activeTab = VIEW_ACTIVITY;
            refreshConnectionStatus();
        });
        addHomeSecondaryAction(activity, 15);
    }

    private void addHomeSecondaryAction(LinearLayout row, int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ConsoleTheme.dp(content, 76)
        );
        params.setMargins(0, ConsoleTheme.dp(content, topMarginDp), 0, 0);
        tabContent.addView(row, params);
    }

    private void renderSettingsScreen() {
        PairingCredential credential = new SecureTokenStore(this).loadCredential();
        String macBaseUrl = PairingStore.loadMacBaseUrl(this);
        renderPermissionTab(homeState(credential != null && !credential.requiresSecureRePairing() && !macBaseUrl.isEmpty(), macBaseUrl));
    }

    private void renderConnectionDetailsScreen(PairingCredential credential, String macBaseUrl) {
        tabContent.setPadding(ConsoleTheme.dp(content, 4), 0, ConsoleTheme.dp(content, 4), 0);
        ForwardingStatsStore stats = ForwardingStatsStore.load(this);
        String appVersion = appVersionName();
        ConnectionDetailsUiState state = ConnectionDetailsUiState.from(
            listenerRunning,
            localIpAddress(),
            macBaseUrl,
            !discoveredServices.isEmpty(),
            credential == null ? "" : credential.deviceId,
            stats.lastReceivedAt,
            stats.lastForwardedAt,
            appVersion,
            connectionDetailsCopy()
        );
        renderDetailsStatusCard(state);

        LinearLayout network = detailSection(getString(R.string.android_connection_network_config));
        addGroupedDetailRow(network, R.drawable.ic_bridge_wifi, ConsoleTheme.ACCENT_TEAL, getString(R.string.android_connection_network_type), "", getString(R.string.android_connection_lan), ConsoleTheme.TEXT_SECONDARY, false);
        addGroupedDetailDivider(network);
        addGroupedDetailRow(network, R.drawable.ic_bridge_phone, ConsoleTheme.ACCENT_TEAL, getString(R.string.android_connection_android_ip), "", localIpAddress(), ConsoleTheme.TEXT_SECONDARY, false);
        addGroupedDetailDivider(network);
        addGroupedDetailRow(network, R.drawable.ic_bridge_monitor, ConsoleTheme.ACCENT_TEAL, getString(R.string.android_connection_mac_os_ip), "", macHost(macBaseUrl), ConsoleTheme.TEXT_SECONDARY, false);
        addGroupedDetailDivider(network);
        addGroupedDetailRow(network, R.drawable.ic_bridge_target, ConsoleTheme.ACCENT_TEAL, getString(R.string.android_connection_bonjour_service), "", !discoveredServices.isEmpty() ? getString(R.string.android_connection_active) : getString(R.string.android_connection_not_found), !discoveredServices.isEmpty() ? ConsoleTheme.ACCENT_LIME : ConsoleTheme.ACCENT_AMBER, true);
        tabContent.addView(network);

        LinearLayout security = detailSection(getString(R.string.android_connection_security));
        addGroupedDetailRow(security, R.drawable.ic_bridge_link, ConsoleTheme.ACCENT_TEAL, getString(R.string.android_connection_auth_method), secureAuthText(credential), credential == null ? getString(R.string.android_connection_not_generated) : getString(R.string.android_connection_valid), credential == null ? ConsoleTheme.ACCENT_AMBER : ConsoleTheme.ACCENT_LIME, false);
        addGroupedDetailDivider(security);
        addGroupedDetailRow(security, R.drawable.ic_bridge_shield, ConsoleTheme.ACCENT_TEAL, getString(R.string.android_connection_transfer_method), "", state.securityText, ConsoleTheme.TEXT_SECONDARY, false);
        tabContent.addView(security);

        LinearLayout technical = detailSection(getString(R.string.android_connection_technical_specs));
        addGroupedDetailRow(technical, R.drawable.ic_bridge_globe, ConsoleTheme.TEXT_SECONDARY, getString(R.string.android_connection_port), "", macPort(macBaseUrl), ConsoleTheme.TEXT_SECONDARY, false);
        addGroupedDetailDivider(technical);
        addGroupedDetailRow(technical, R.drawable.ic_bridge_target, ConsoleTheme.TEXT_SECONDARY, getString(R.string.android_connection_device_id), "", shortDeviceId(credential), ConsoleTheme.TEXT_SECONDARY, false);
        addGroupedDetailDivider(technical);
        addGroupedDetailRow(technical, R.drawable.ic_bridge_clipboard, ConsoleTheme.TEXT_SECONDARY, getString(R.string.android_connection_app_version), "", "v" + appVersion, ConsoleTheme.TEXT_SECONDARY, false);
        tabContent.addView(technical);

        renderDetailsBottomActions();
    }

    private void renderActivityScreen() {
        MessageEventStore store = new MessageEventStore(this);
        MessageActivityUiState state;
        try {
            state = MessageActivityUiState.from(store.recentActivity(100), messageActivityCopy(), messageListCopy());
        } finally {
            store.close();
        }
        if (state.rows.isEmpty()) {
            LinearLayout empty = ConsoleTheme.panel(content);
            empty.addView(ConsoleTheme.label(content, getString(R.string.android_message_no_recent_activity), 20, ConsoleTheme.TEXT_PRIMARY, Typeface.BOLD));
            TextView detail = ConsoleTheme.label(content, getString(R.string.android_message_activity_empty_detail), 14, ConsoleTheme.TEXT_SECONDARY, Typeface.NORMAL);
            LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            detailParams.setMargins(0, ConsoleTheme.dp(content, 8), 0, 0);
            empty.addView(detail, detailParams);
            tabContent.addView(empty);
            return;
        }
        for (MessageActivityUiState.Row row : state.rows) {
            renderActivityCard(row);
        }
    }

    private String recentActivitySubtitle() {
        MessageEventStore store = new MessageEventStore(this);
        MessageActivityUiState state;
        try {
            state = MessageActivityUiState.from(store.recentActivity(1), messageActivityCopy(), messageListCopy());
        } finally {
            store.close();
        }
        if (state.rows.isEmpty()) return getString(R.string.android_message_no_activity);
        MessageActivityUiState.Row row = state.rows.get(0);
        String time = messageTimeText(row.receivedAt);
        return time.isEmpty() ? row.statusText : row.statusText + " · " + time;
    }

    private void renderDeviceStatusCard(boolean paired, boolean permissionsOk, String macBaseUrl) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(ConsoleTheme.gradient(content, ConsoleTheme.SURFACE, Color.rgb(226, 241, 255), 12, ConsoleTheme.colorWithAlpha(ConsoleTheme.STROKE, 55), 1));
        card.setElevation(ConsoleTheme.dp(content, 6));
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(ConsoleTheme.dp(content, 18), ConsoleTheme.dp(content, 32), ConsoleTheme.dp(content, 18), ConsoleTheme.dp(content, 24));
        boolean connected = paired && permissionsOk && listenerRunning;
        int color = connected ? ConsoleTheme.ACCENT_LIME : ConsoleTheme.ACCENT_AMBER;
        String chipText = connected ? getString(R.string.android_home_realtime_active) : getString(R.string.android_home_waiting_connection);
        String title = !permissionsOk ? getString(R.string.android_home_permission_limited) : connected ? getString(R.string.android_home_connected_to) : paired ? getString(R.string.android_home_waiting_connection) : getString(R.string.android_home_needs_pairing);
        String subtitle = !permissionsOk ? getString(R.string.android_home_permissions_required_subtitle) : connected ? getString(R.string.android_home_pushing_in_background) : paired ? getString(R.string.android_home_waiting_mac) : getString(R.string.android_home_connect_mac);

        addWrappedChip(card, chipText, color, Gravity.CENTER_HORIZONTAL);
        TextView titleView = ConsoleTheme.label(content, title, 31, ConsoleTheme.TEXT_PRIMARY, Typeface.BOLD);
        titleView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(0, ConsoleTheme.dp(content, 28), 0, 0);
        card.addView(titleView, titleParams);
        if (connected) {
            TextView desktopName = connectedDesktopNameLabel(macBaseUrl);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nameParams.setMargins(0, ConsoleTheme.dp(content, 4), 0, 0);
            card.addView(desktopName, nameParams);
        }
        TextView subtitleView = ConsoleTheme.label(content, subtitle, 16, ConsoleTheme.TEXT_SECONDARY, Typeface.NORMAL);
        subtitleView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleParams.setMargins(0, ConsoleTheme.dp(content, 12), 0, 0);
        card.addView(subtitleView, subtitleParams);

        LinearLayout topology = new LinearLayout(this);
        topology.setOrientation(LinearLayout.HORIZONTAL);
        topology.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams topologyParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        topologyParams.setMargins(0, ConsoleTheme.dp(content, 43), 0, 0);
        DeviceTopologySizing.Layout topologySizing = DeviceTopologySizing.forScreenWidthDp(currentScreenWidthDp());
        DeviceTopologyConnectorMotion connectorMotion = DeviceTopologyConnectorMotion.from(paired, connected);
        topology.addView(heroTopologyNode(R.drawable.ic_bridge_phone, getString(R.string.android_home_current_phone), 64, 110, 30, 0), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        topology.addView(heroConnector(paired, topologySizing.connectorWidthDp, connectorMotion));
        topology.addView(heroTopologyNode(
            R.drawable.ic_bridge_laptop,
            shortMacDisplayName(macBaseUrl),
            topologySizing.laptopFrameWidthDp,
            topologySizing.laptopFrameHeightDp,
            topologySizing.laptopIconSizeDp,
            topologySizing.laptopBaseWidthDp
        ), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(topology, topologyParams);

        Button rePair = ConsoleTheme.roundedButton(content, paired ? getString(R.string.android_home_repair_device) : getString(R.string.android_home_pair_device), true);
        rePair.setTextSize(18);
        rePair.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(ConsoleTheme.dp(content, 184), ConsoleTheme.dp(content, 49));
        buttonParams.gravity = Gravity.CENTER_HORIZONTAL;
        buttonParams.setMargins(0, ConsoleTheme.dp(content, 58), 0, 0);
        rePair.setOnClickListener(v -> {
            if (paired) {
                clearStoredPairingForDeviceSelection();
            } else {
                openDeviceSelection();
            }
        });
        card.addView(rePair, buttonParams);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cardParams.setMargins(0, ConsoleTheme.dp(content, 4), 0, 0);
        tabContent.addView(card, cardParams);
    }

    private void openDeviceSelection() {
        pairingBaseUrl = "";
        pairingServiceName = "";
        activeTab = VIEW_DEVICE_SELECTION;
        sectionError.setVisibility(View.GONE);
        refreshConnectionStatus();
    }

    private void clearStoredPairingForDeviceSelection() {
        activeTab = VIEW_DEVICE_SELECTION;
        clearStoredPairing(getString(R.string.android_home_pair_cleared));
    }

    private void renderDeviceSelectionScreen() {
        tabContent.setBackgroundColor(Color.WHITE);
        tabContent.setPadding(ConsoleTheme.dp(content, 14), 0, ConsoleTheme.dp(content, 14), ConsoleTheme.dp(content, 44));

        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setGravity(Gravity.CENTER_HORIZONTAL);
        screen.setPadding(0, ConsoleTheme.dp(content, 64), 0, ConsoleTheme.dp(content, 32));
        tabContent.addView(screen, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        FrameLayout searchIcon = new FrameLayout(this);
        searchIcon.setContentDescription(getString(R.string.android_discovery_rescan));
        searchIcon.setBackground(ConsoleTheme.rounded(content, Color.rgb(215, 235, 255), 99, ConsoleTheme.colorWithAlpha(ConsoleTheme.ACCENT_TEAL, 0), 0));
        searchIcon.addView(iconView(R.drawable.ic_bridge_refresh, ConsoleTheme.ACCENT_TEAL_DARK, 34), new FrameLayout.LayoutParams(
            ConsoleTheme.dp(content, 34),
            ConsoleTheme.dp(content, 34),
            Gravity.CENTER
        ));
        searchIcon.setOnClickListener(v -> refreshDeviceDiscovery(searchIcon));
        Motion.applyPressScale(searchIcon);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(ConsoleTheme.dp(content, 64), ConsoleTheme.dp(content, 64));
        iconParams.gravity = Gravity.CENTER_HORIZONTAL;
        screen.addView(searchIcon, iconParams);

        TextView title = ConsoleTheme.label(content, getString(R.string.android_discovery_searching), 24, Color.rgb(26, 27, 31), Typeface.NORMAL);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(0, ConsoleTheme.dp(content, 20), 0, 0);
        screen.addView(title, titleParams);

        TextView subtitle = ConsoleTheme.label(content, getString(R.string.android_discovery_same_wifi_hint), 16, Color.rgb(65, 71, 85), Typeface.NORMAL);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setAlpha(0.86f);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleParams.setMargins(0, ConsoleTheme.dp(content, 16), 0, 0);
        screen.addView(subtitle, subtitleParams);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, 0);
        list.setBackground(ConsoleTheme.rounded(content, Color.WHITE, 12, ConsoleTheme.colorWithAlpha(ConsoleTheme.STROKE, 70), 1));
        list.setElevation(ConsoleTheme.dp(content, 8));
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        listParams.setMargins(0, ConsoleTheme.dp(content, 48), 0, 0);
        screen.addView(list, listParams);

        int rendered = 0;
        for (NsdServiceInfo service : discoveredServices) {
            String baseUrl = MacEndpointUrls.baseUrl(service);
            if (baseUrl == null) continue;
            if (rendered > 0) list.addView(deviceSelectionDivider());
            LinearLayout row = deviceSelectionRow(deviceSelectionIcon(service.getServiceName()), service.getServiceName(), getString(R.string.android_discovery_tap_to_pair));
            row.setOnClickListener(v -> showPairingForm(endpointFromService(service, baseUrl)));
            list.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ConsoleTheme.dp(content, 72)));
            Motion.staggerFadeInUp(row, rendered);
            rendered++;
        }
        if (rendered == 0) {
            LinearLayout row = deviceSelectionRow(R.drawable.ic_bridge_laptop, getString(R.string.android_discovery_empty), getString(R.string.android_discovery_empty_hint));
            list.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ConsoleTheme.dp(content, 72)
            ));
            Motion.staggerFadeInUp(row, rendered);
        }

        TextView manual = ConsoleTheme.label(content, getString(R.string.android_discovery_manual), 14, ConsoleTheme.ACCENT_TEAL_DARK, Typeface.BOLD);
        manual.setGravity(Gravity.CENTER);
        LinearLayout manualTarget = new LinearLayout(this);
        manualTarget.setGravity(Gravity.CENTER);
        manualTarget.setOnClickListener(v -> {
            activeTab = VIEW_MANUAL_PAIRING;
            sectionError.setVisibility(View.GONE);
            refreshConnectionStatus();
        });
        Motion.applyPressScale(manualTarget);
        manualTarget.addView(manual, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams manualParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ConsoleTheme.dp(content, 52));
        manualParams.setMargins(0, ConsoleTheme.dp(content, 18), 0, 0);
        screen.addView(manualTarget, manualParams);
    }

    private void refreshDeviceDiscovery(View refreshIcon) {
        Motion.breathe(refreshIcon);
        discoveredServices = new ArrayList<>();
        restartDiscovery();
    }

    private LinearLayout deviceSelectionRow(int icon, String title, String subtitle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ConsoleTheme.dp(content, 16), 0, ConsoleTheme.dp(content, 14), 0);
        Motion.applyPressScale(row);
        row.setBackground(ConsoleTheme.rounded(content, Color.WHITE, 0, ConsoleTheme.colorWithAlpha(Color.WHITE, 0), 0));

        FrameLayout iconFrame = new FrameLayout(this);
        iconFrame.setBackground(ConsoleTheme.rounded(content, Color.rgb(238, 237, 243), 6, ConsoleTheme.colorWithAlpha(ConsoleTheme.STROKE, 0), 0));
        iconFrame.addView(iconView(icon, Color.rgb(65, 71, 85), 27), new FrameLayout.LayoutParams(
            ConsoleTheme.dp(content, 27),
            ConsoleTheme.dp(content, 27),
            Gravity.CENTER
        ));
        row.addView(iconFrame, new LinearLayout.LayoutParams(ConsoleTheme.dp(content, 42), ConsoleTheme.dp(content, 42)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        labelsParams.setMargins(ConsoleTheme.dp(content, 16), 0, ConsoleTheme.dp(content, 8), 0);
        TextView titleView = singleLine(ConsoleTheme.label(content, title, 18, Color.rgb(26, 27, 31), Typeface.BOLD));
        labels.addView(titleView);
        TextView subtitleView = singleLine(ConsoleTheme.label(content, subtitle, 13, ConsoleTheme.ACCENT_TEAL_DARK, Typeface.NORMAL));
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subParams.setMargins(0, ConsoleTheme.dp(content, 4), 0, 0);
        labels.addView(subtitleView, subParams);
        row.addView(labels, labelsParams);

        TextView arrow = ConsoleTheme.label(content, "›", 31, Color.rgb(188, 196, 210), Typeface.NORMAL);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(ConsoleTheme.dp(content, 20), ViewGroup.LayoutParams.MATCH_PARENT));
        return row;
    }

    private View deviceSelectionDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(229, 231, 236));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, ConsoleTheme.dp(content, 1)));
        params.setMargins(ConsoleTheme.dp(content, 64), 0, 0, 0);
        divider.setLayoutParams(params);
        return divider;
    }

    private int deviceSelectionIcon(String serviceName) {
        String name = serviceName == null ? "" : serviceName.toLowerCase();
        if (name.contains("imac") || name.contains("studio") || name.contains("mini")) return R.drawable.ic_bridge_monitor;
        if (name.contains("ipad") || name.contains("phone")) return R.drawable.ic_bridge_phone;
        return R.drawable.ic_bridge_laptop;
    }

    private TextView connectedDesktopNameLabel(String macBaseUrl) {
        TextView view = singleLine(ConsoleTheme.label(content, shortMacDisplayName(macBaseUrl), 31, ConsoleTheme.TEXT_PRIMARY, Typeface.BOLD));
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private LinearLayout deviceActionRow(int icon, String title, String subtitle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ConsoleTheme.dp(content, 18), 0, ConsoleTheme.dp(content, 16), 0);
        row.setMinimumHeight(ConsoleTheme.dp(content, 76));
        row.setBackground(ConsoleTheme.rounded(content, ConsoleTheme.SURFACE, 12, ConsoleTheme.colorWithAlpha(ConsoleTheme.STROKE, 45), 1));
        row.setElevation(ConsoleTheme.dp(content, 4));
        row.addView(actionIconFrame(icon, ConsoleTheme.ACCENT_TEAL, 40, 22));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        textParams.setMargins(ConsoleTheme.dp(content, 16), 0, ConsoleTheme.dp(content, 10), 0);
        text.addView(ConsoleTheme.label(content, title, 19, ConsoleTheme.TEXT_PRIMARY, Typeface.NORMAL));
        TextView subtitleView = ConsoleTheme.label(content, subtitle == null ? "" : subtitle, 13, ConsoleTheme.TEXT_SECONDARY, Typeface.NORMAL);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleParams.setMargins(0, ConsoleTheme.dp(content, 5), 0, 0);
        text.addView(subtitleView, subtitleParams);
        row.addView(text, textParams);
        TextView arrow = ConsoleTheme.label(content, "›", 28, Color.rgb(188, 196, 210), Typeface.NORMAL);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(ConsoleTheme.dp(content, 16), ViewGroup.LayoutParams.MATCH_PARENT));
        return row;
    }

    private LinearLayout heroTopologyNode(int iconRes, String title, int frameWidthDp, int frameHeightDp, int iconSizeDp, int laptopBaseWidthDp) {
        LinearLayout node = new LinearLayout(this);
        node.setOrientation(LinearLayout.VERTICAL);
        node.setGravity(Gravity.CENTER_HORIZONTAL);

        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(ConsoleTheme.rounded(content, ConsoleTheme.colorWithAlpha(ConsoleTheme.SURFACE_ALT, 190), iconRes == R.drawable.ic_bridge_phone ? 18 : 12, Color.rgb(196, 202, 220), 3));
        ImageView heroIcon = iconView(iconRes, ConsoleTheme.ACCENT_TEAL, iconSizeDp);
        frame.addView(heroIcon, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        if (iconRes == R.drawable.ic_bridge_phone) {
            View notch = new View(this);
            notch.setBackground(ConsoleTheme.rounded(content, Color.rgb(196, 202, 220), 99, Color.rgb(196, 202, 220), 0));
            FrameLayout.LayoutParams notchParams = new FrameLayout.LayoutParams(ConsoleTheme.dp(content, 17), ConsoleTheme.dp(content, 4), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            notchParams.setMargins(0, ConsoleTheme.dp(content, 9), 0, 0);
            frame.addView(notch, notchParams);
        }
        LinearLayout.LayoutParams frameParams = new LinearLayout.LayoutParams(ConsoleTheme.dp(content, frameWidthDp), ConsoleTheme.dp(content, frameHeightDp));
        frameParams.gravity = Gravity.CENTER_HORIZONTAL;
        node.addView(frame, frameParams);
        if (iconRes == R.drawable.ic_bridge_laptop) {
            View base = new View(this);
            base.setBackground(ConsoleTheme.rounded(content, Color.rgb(196, 202, 220), 4, Color.rgb(196, 202, 220), 0));
            LinearLayout.LayoutParams baseParams = new LinearLayout.LayoutParams(ConsoleTheme.dp(content, laptopBaseWidthDp), ConsoleTheme.dp(content, 5));
            baseParams.gravity = Gravity.CENTER_HORIZONTAL;
            baseParams.setMargins(0, -ConsoleTheme.dp(content, 2), 0, 0);
            node.addView(base, baseParams);
        }

        TextView titleView = topologyText(title, 13, ConsoleTheme.TEXT_PRIMARY, Typeface.NORMAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(0, ConsoleTheme.dp(content, 12), 0, 0);
        node.addView(titleView, titleParams);
        return node;
    }

    private View heroConnector(boolean active, int widthDp, DeviceTopologyConnectorMotion motion) {
        FrameLayout connector = new FrameLayout(this);
        connector.setClipChildren(false);
        connector.setClipToPadding(false);
        connector.setMinimumWidth(ConsoleTheme.dp(content, widthDp));
        connector.setLayoutParams(new LinearLayout.LayoutParams(ConsoleTheme.dp(content, widthDp), ConsoleTheme.dp(content, 12)));

        View line = new View(this);
        int color = active ? ConsoleTheme.ACCENT_TEAL : ConsoleTheme.TEXT_MUTED;
        line.setBackground(ConsoleTheme.gradient(content, ConsoleTheme.colorWithAlpha(color, 20), color, 99, ConsoleTheme.colorWithAlpha(color, 0), 0));
        line.setAlpha(motion.mode == DeviceTopologyConnectorMotion.Mode.PULSE ? 0.45f : 1f);
        connector.addView(line, new FrameLayout.LayoutParams(
            ConsoleTheme.dp(content, widthDp),
            ConsoleTheme.dp(content, 2),
            Gravity.CENTER
        ));

        if (motion.mode == DeviceTopologyConnectorMotion.Mode.PULSE) {
            ObjectAnimator pulse = ObjectAnimator.ofFloat(line, View.ALPHA, 0.35f, 1f);
            pulse.setDuration(motion.durationMs);
            pulse.setRepeatCount(ValueAnimator.INFINITE);
            pulse.setRepeatMode(ValueAnimator.REVERSE);
            pulse.setInterpolator(new AccelerateDecelerateInterpolator());
            pulse.start();
            connector.addOnAttachStateChangeListener(cancelAnimatorOnDetach(pulse));
        } else if (motion.mode == DeviceTopologyConnectorMotion.Mode.SWEEP) {
            View sweep = new View(this);
            int segmentWidth = ConsoleTheme.dp(content, motion.segmentWidthDp);
            GradientDrawable sweepDrawable = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {
                    ConsoleTheme.colorWithAlpha(color, 0),
                    ConsoleTheme.colorWithAlpha(color, 130),
                    ConsoleTheme.colorWithAlpha(color, 0)
                }
            );
            sweepDrawable.setCornerRadius(ConsoleTheme.dp(content, 99));
            sweep.setBackground(sweepDrawable);
            connector.addView(sweep, new FrameLayout.LayoutParams(
                segmentWidth,
                ConsoleTheme.dp(content, 8),
                Gravity.LEFT | Gravity.CENTER_VERTICAL
            ));
            sweep.setTranslationX(-segmentWidth);
            ObjectAnimator sweepAnimator = ObjectAnimator.ofFloat(
                sweep,
                View.TRANSLATION_X,
                -segmentWidth,
                ConsoleTheme.dp(content, widthDp)
            );
            sweepAnimator.setDuration(motion.durationMs);
            sweepAnimator.setRepeatCount(ValueAnimator.INFINITE);
            sweepAnimator.setInterpolator(new LinearInterpolator());
            sweepAnimator.start();
            connector.addOnAttachStateChangeListener(cancelAnimatorOnDetach(sweepAnimator));
        }

        return connector;
    }

    private View.OnAttachStateChangeListener cancelAnimatorOnDetach(ObjectAnimator animator) {
        return new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
                if (!animator.isStarted()) animator.start();
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
                animator.cancel();
            }
        };
    }

    private int currentScreenWidthDp() {
        Configuration configuration = getResources().getConfiguration();
        if (configuration.screenWidthDp > 0) return configuration.screenWidthDp;
        return Math.round(getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density);
    }

    private LinearLayout actionIconFrame(int iconRes, int color) {
        return actionIconFrame(iconRes, color, 58, 28);
    }

    private LinearLayout actionIconFrame(int iconRes, int color, int frameSizeDp, int iconSizeDp) {
        LinearLayout frame = new LinearLayout(this);
        frame.setGravity(Gravity.CENTER);
        frame.setBackground(ConsoleTheme.rounded(content, ConsoleTheme.colorWithAlpha(color, 22), 99, ConsoleTheme.colorWithAlpha(color, 0), 0));
        frame.addView(iconView(iconRes, color, iconSizeDp));
        frame.setLayoutParams(new LinearLayout.LayoutParams(ConsoleTheme.dp(content, frameSizeDp), ConsoleTheme.dp(content, frameSizeDp)));
        return frame;
    }

    private void renderDetailsStatusCard(ConnectionDetailsUiState state) {
        LinearLayout card = detailCard();
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(ConsoleTheme.dp(content, 18), ConsoleTheme.dp(content, 32), ConsoleTheme.dp(content, 18), ConsoleTheme.dp(content, 24));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ConsoleTheme.dp(content, 215)
        );
        cardParams.setMargins(0, ConsoleTheme.dp(content, 8), 0, 0);
        card.setLayoutParams(cardParams);

        boolean connected = state.connected;
        FrameLayout iconShell = new FrameLayout(this);
        iconShell.setForegroundGravity(Gravity.CENTER);
        int statusColor = connected ? ConsoleTheme.ACCENT_LIME : ConsoleTheme.ACCENT_AMBER;
        iconShell.setBackground(ConsoleTheme.rounded(content, ConsoleTheme.colorWithAlpha(statusColor, 82), 99, ConsoleTheme.colorWithAlpha(statusColor, 0), 0));
        TextView check = ConsoleTheme.label(content, connected ? "✓" : "!", 21, Color.WHITE, Typeface.BOLD);
        check.setGravity(Gravity.CENTER);
        check.setBackground(ConsoleTheme.rounded(content, connected ? Color.rgb(0, 122, 61) : ConsoleTheme.ACCENT_AMBER, 99, ConsoleTheme.colorWithAlpha(statusColor, 0), 0));
        iconShell.addView(check, new FrameLayout.LayoutParams(
            ConsoleTheme.dp(content, 32),
            ConsoleTheme.dp(content, 32),
            Gravity.CENTER
        ));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(ConsoleTheme.dp(content, 80), ConsoleTheme.dp(content, 80));
        iconParams.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(iconShell, iconParams);

        TextView title = ConsoleTheme.label(content, state.title, 24, ConsoleTheme.TEXT_PRIMARY, Typeface.NORMAL);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(0, ConsoleTheme.dp(content, 18), 0, 0);
        card.addView(title, titleParams);

        LinearLayout statusLine = new LinearLayout(this);
        statusLine.setGravity(Gravity.CENTER);
        statusLine.addView(detailStatusDot(statusColor, 7));
        TextView subtitle = ConsoleTheme.label(content, connected ? getString(R.string.android_connection_active_pushing) : getString(R.string.android_connection_waiting), 13, ConsoleTheme.TEXT_SECONDARY, Typeface.NORMAL);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleParams.setMargins(ConsoleTheme.dp(content, 7), 0, 0, 0);
        statusLine.addView(subtitle, subtitleParams);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.setMargins(0, ConsoleTheme.dp(content, 10), 0, 0);
        card.addView(statusLine, statusParams);

        tabContent.addView(card);
    }

    private LinearLayout detailSection(String title) {
        LinearLayout section = detailCard();
        section.setPadding(ConsoleTheme.dp(content, 22), ConsoleTheme.dp(content, 20), ConsoleTheme.dp(content, 16), ConsoleTheme.dp(content, 16));
        TextView heading = ConsoleTheme.label(content, title, 12, ConsoleTheme.TEXT_SECONDARY, Typeface.NORMAL);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headingParams.setMargins(0, 0, 0, ConsoleTheme.dp(content, 10));
        section.addView(heading, headingParams);
        return section;
    }

    private LinearLayout detailCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(ConsoleTheme.rounded(content, ConsoleTheme.SURFACE, 16, ConsoleTheme.colorWithAlpha(ConsoleTheme.STROKE, 32), 1));
        card.setElevation(ConsoleTheme.dp(content, 3));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, ConsoleTheme.dp(content, 16), 0, 0);
        card.setLayoutParams(params);
        return card;
    }

    private void addGroupedDetailRow(LinearLayout section, int iconRes, int iconColor, String title, String subtitle, String value, int valueColor, boolean chipValue) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, 0);

        row.addView(detailIconFrame(iconRes, iconColor), new LinearLayout.LayoutParams(ConsoleTheme.dp(content, 32), ConsoleTheme.dp(content, 32)));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        textParams.setMargins(ConsoleTheme.dp(content, 14), 0, ConsoleTheme.dp(content, 10), 0);
        text.addView(singleLine(ConsoleTheme.label(content, title, 15, ConsoleTheme.TEXT_PRIMARY, Typeface.NORMAL)));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView subtitleView = singleLine(ConsoleTheme.label(content, subtitle, 11, ConsoleTheme.TEXT_SECONDARY, Typeface.NORMAL));
            LinearLayout.LayoutParams subtitleViewParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subtitleViewParams.setMargins(0, ConsoleTheme.dp(content, 3), 0, 0);
            text.addView(subtitleView, subtitleViewParams);
        }
        row.addView(text, textParams);

        if (chipValue) {
            row.addView(detailValueChip(value == null || value.isEmpty() ? "-" : value, valueColor));
        } else {
            TextView valueView = singleLine(ConsoleTheme.label(content, value == null || value.isEmpty() ? "-" : value, 14, valueColor, Typeface.NORMAL));
            valueView.setGravity(Gravity.RIGHT);
            row.addView(valueView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        section.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ConsoleTheme.dp(content, 56)));
    }

    private void addGroupedDetailDivider(LinearLayout section) {
        View divider = ConsoleTheme.divider(content);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, ConsoleTheme.dp(content, 1)));
        params.setMargins(ConsoleTheme.dp(content, 52), 0, 0, 0);
        section.addView(divider, params);
    }

    private LinearLayout detailIconFrame(int iconRes, int color) {
        LinearLayout frame = new LinearLayout(this);
        frame.setGravity(Gravity.CENTER);
        frame.setBackground(ConsoleTheme.rounded(content, ConsoleTheme.colorWithAlpha(color, 22), 99, ConsoleTheme.colorWithAlpha(color, 0), 0));
        frame.addView(iconView(iconRes, color, 18));
        return frame;
    }

    private View detailStatusDot(int color, int sizeDp) {
        View dot = new View(this);
        dot.setBackground(ConsoleTheme.rounded(content, color, 99, color, 0));
        dot.setLayoutParams(new LinearLayout.LayoutParams(ConsoleTheme.dp(content, sizeDp), ConsoleTheme.dp(content, sizeDp)));
        return dot;
    }

    private LinearLayout detailValueChip(String text, int color) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(ConsoleTheme.dp(content, 8), ConsoleTheme.dp(content, 4), ConsoleTheme.dp(content, 8), ConsoleTheme.dp(content, 4));
        chip.setBackground(ConsoleTheme.rounded(content, ConsoleTheme.colorWithAlpha(color, 28), 99, ConsoleTheme.colorWithAlpha(color, 0), 0));
        chip.addView(detailStatusDot(color, 5));
        TextView label = ConsoleTheme.label(content, text, 11, color, Typeface.NORMAL);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.setMargins(ConsoleTheme.dp(content, 4), 0, 0, 0);
        chip.addView(label, labelParams);
        return chip;
    }

    private void addWrappedChip(LinearLayout parent, String text, int color, int gravity) {
        LinearLayout chip = statusPill(text, color);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.gravity = gravity;
        parent.addView(chip, params);
    }

    private LinearLayout statusPill(String text, int color) {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(ConsoleTheme.dp(content, 9), ConsoleTheme.dp(content, 5), ConsoleTheme.dp(content, 9), ConsoleTheme.dp(content, 5));
        pill.setBackground(ConsoleTheme.rounded(content, ConsoleTheme.colorWithAlpha(color, 24), 99, ConsoleTheme.colorWithAlpha(color, 54), 1));

        View dot = new View(this);
        dot.setBackground(ConsoleTheme.rounded(content, color, 99, color, 0));
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(ConsoleTheme.dp(content, 6), ConsoleTheme.dp(content, 6));
        pill.addView(dot, dotParams);

        TextView label = ConsoleTheme.label(content, text, 12, color, Typeface.BOLD);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.setMargins(ConsoleTheme.dp(content, 6), 0, 0, 0);
        pill.addView(label, labelParams);
        return pill;
    }

    private View statusDot(int color, int sizeDp) {
        View dot = new View(this);
        dot.setBackground(ConsoleTheme.rounded(content, color, 99, ConsoleTheme.colorWithAlpha(color, 90), 1));
        dot.setLayoutParams(new LinearLayout.LayoutParams(ConsoleTheme.dp(content, sizeDp), ConsoleTheme.dp(content, sizeDp)));
        return dot;
    }

    private LinearLayout iconActionButton(String text, int iconRes, boolean primary, boolean enabled, View.OnClickListener click) {
        LinearLayout button = new LinearLayout(this);
        button.setOrientation(LinearLayout.HORIZONTAL);
        button.setGravity(Gravity.CENTER);
        button.setFocusable(enabled);
        button.setClickable(enabled);
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.62f);
        int fill = primary ? ConsoleTheme.ACCENT_TEAL_DARK : ConsoleTheme.SURFACE_ALT;
        int stroke = primary ? ConsoleTheme.ACCENT_TEAL : ConsoleTheme.colorWithAlpha(ConsoleTheme.STROKE, 0);
        int textColor = primary ? Color.WHITE : ConsoleTheme.TEXT_PRIMARY;
        if (!enabled) {
            fill = ConsoleTheme.SURFACE_ALT;
            stroke = ConsoleTheme.STROKE;
            textColor = ConsoleTheme.TEXT_SECONDARY;
        }
        button.setBackground(ConsoleTheme.rounded(content, fill, 12, stroke, 1));
        button.setPadding(ConsoleTheme.dp(content, 12), ConsoleTheme.dp(content, 8), ConsoleTheme.dp(content, 12), ConsoleTheme.dp(content, 8));
        button.addView(iconView(iconRes, textColor, 18));
        TextView label = ConsoleTheme.label(content, text, 15, textColor, Typeface.BOLD);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.setMargins(ConsoleTheme.dp(content, 8), 0, 0, 0);
        button.addView(label, labelParams);
        if (enabled && click != null) button.setOnClickListener(click);
        if (enabled) Motion.applyPressScale(button);
        return button;
    }

    private void renderPendingMessageCard(MessageListUiState.Row row) {
        LinearLayout card = ConsoleTheme.panel(content);
        card.setPadding(ConsoleTheme.dp(content, 14), ConsoleTheme.dp(content, 14), ConsoleTheme.dp(content, 14), ConsoleTheme.dp(content, 14));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.TOP);

        LinearLayout iconFrame = new LinearLayout(this);
        iconFrame.setGravity(Gravity.CENTER);
        iconFrame.setBackground(ConsoleTheme.rounded(content, ConsoleTheme.colorWithAlpha(ConsoleTheme.ACCENT_TEAL, 24), 99, ConsoleTheme.colorWithAlpha(ConsoleTheme.ACCENT_TEAL, 0), 0));
        iconFrame.addView(iconView(R.drawable.ic_bridge_sms, ConsoleTheme.ACCENT_TEAL, 28));
        top.addView(iconFrame, new LinearLayout.LayoutParams(ConsoleTheme.dp(content, 48), ConsoleTheme.dp(content, 48)));

        LinearLayout title = new LinearLayout(this);
        title.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        titleParams.setMargins(ConsoleTheme.dp(content, 12), 0, ConsoleTheme.dp(content, 8), 0);
        title.addView(singleLine(ConsoleTheme.label(content, row.sender, 18, ConsoleTheme.TEXT_PRIMARY, Typeface.NORMAL)));
        TextView time = ConsoleTheme.label(content, messageTimeText(row.receivedAt), 12, ConsoleTheme.TEXT_SECONDARY, Typeface.BOLD);
        LinearLayout.LayoutParams timeParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        timeParams.setMargins(0, ConsoleTheme.dp(content, 3), 0, 0);
        title.addView(time, timeParams);
        top.addView(title, titleParams);
        top.addView(statusPill(row.statusText, messageStatusColor(row.status)));
        card.addView(top);

        TextView body = ConsoleTheme.label(content, row.bodyPreview, 16, ConsoleTheme.TEXT_SECONDARY, Typeface.NORMAL);
        body.setMaxLines(2);
        body.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyParams.setMargins(ConsoleTheme.dp(content, 60), ConsoleTheme.dp(content, 12), 0, 0);
        card.addView(body, bodyParams);
        tabContent.addView(card);
    }

    private void renderDetailsBottomActions() {
        prepareTaskBottomSurface();
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(ConsoleTheme.dp(content, 20), ConsoleTheme.dp(content, 16), ConsoleTheme.dp(content, 20), ConsoleTheme.dp(content, 16));

        LinearLayout copy = iconActionButton(getString(R.string.android_connection_copy_debug_log), R.drawable.ic_bridge_clipboard, false, true, v -> copyDiagnostics());
        actions.addView(copy, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ConsoleTheme.dp(content, 46)));

        LinearLayout test = iconActionButton(connectionTestUiState.buttonLabel, R.drawable.ic_bridge_pulse, true, connectionTestUiState.buttonEnabled, v -> testConnection());
        LinearLayout.LayoutParams testParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ConsoleTheme.dp(content, 46));
        testParams.setMargins(0, ConsoleTheme.dp(content, 12), 0, 0);
        actions.addView(test, testParams);
        if (connectionTestUiState.feedbackVisible) {
            TextView feedback = connectionTestFeedback(connectionTestUiState);
            LinearLayout.LayoutParams feedbackParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            feedbackParams.setMargins(0, ConsoleTheme.dp(content, 10), 0, 0);
            actions.addView(feedback, feedbackParams);
            Motion.fadeInUp(feedback);
        }
        bottomNavHost.addView(actions);
    }

    private TextView connectionTestFeedback(ConnectionTestUiState state) {
        int color = state.feedbackSuccess
            ? ConsoleTheme.ACCENT_LIME
            : state.buttonEnabled ? ConsoleTheme.ACCENT_RED : ConsoleTheme.TEXT_SECONDARY;
        TextView feedback = ConsoleTheme.label(content, state.feedbackText, 13, color, Typeface.BOLD);
        feedback.setGravity(Gravity.CENTER);
        feedback.setSingleLine(false);
        return feedback;
    }

    private void prepareTaskBottomSurface() {
        bottomNavHost.setBackgroundColor(ConsoleTheme.SURFACE);
    }

    private void renderActivityCard(MessageActivityUiState.Row row) {
        LinearLayout card = activityFeedCard();
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(ConsoleTheme.label(content, row.sender, 13, ConsoleTheme.TEXT_PRIMARY, Typeface.BOLD), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        top.addView(ConsoleTheme.label(content, messageTimeText(row.receivedAt), 11, ConsoleTheme.TEXT_SECONDARY, Typeface.BOLD));
        card.addView(top);

        TextView body = ConsoleTheme.label(content, row.bodyPreview, 13, ConsoleTheme.TEXT_SECONDARY, Typeface.NORMAL);
        body.setMaxLines(2);
        body.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyParams.setMargins(0, ConsoleTheme.dp(content, 8), 0, 0);
        card.addView(body, bodyParams);

        View divider = ConsoleTheme.divider(content);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, ConsoleTheme.dp(content, 1)));
        dividerParams.setMargins(0, ConsoleTheme.dp(content, 10), 0, ConsoleTheme.dp(content, 8));
        card.addView(divider, dividerParams);

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.addView(iconView(R.drawable.ic_bridge_laptop, ConsoleTheme.TEXT_SECONDARY, 14));
        String destination = row.destination == null || row.destination.isEmpty() ? "macOS" : row.destination;
        TextView destinationText = ConsoleTheme.label(content, destination, 11, ConsoleTheme.TEXT_SECONDARY, Typeface.BOLD);
        LinearLayout.LayoutParams destinationParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        destinationParams.setMargins(ConsoleTheme.dp(content, 5), 0, 0, 0);
        footer.addView(destinationText, destinationParams);
        footer.addView(statusPill(row.statusText, messageStatusColor(row.status)));
        card.addView(footer);
        tabContent.addView(card);
    }

    private LinearLayout activityFeedCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(ConsoleTheme.dp(content, 16), ConsoleTheme.dp(content, 14), ConsoleTheme.dp(content, 16), ConsoleTheme.dp(content, 14));
        card.setBackground(ConsoleTheme.rounded(content, ConsoleTheme.SURFACE, 12, ConsoleTheme.colorWithAlpha(ConsoleTheme.STROKE, 45), 1));
        card.setElevation(ConsoleTheme.dp(content, 1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, ConsoleTheme.dp(content, 8), 0, 0);
        card.setLayoutParams(params);
        return card;
    }

    private HomeUiState.Copy homeCopy() {
        return new HomeUiState.Copy(
            getString(R.string.android_home_privacy),
            getString(R.string.android_permission_enabled),
            getString(R.string.android_permission_required),
            getString(R.string.android_permission_sms),
            getString(R.string.android_permission_sms_enabled),
            getString(R.string.android_permission_sms_required),
            getString(R.string.android_permission_enable),
            getString(R.string.android_permission_inbox),
            getString(R.string.android_permission_inbox_enabled),
            getString(R.string.android_permission_inbox_required),
            getString(R.string.android_permission_notification),
            getString(R.string.android_permission_notification_enabled),
            getString(R.string.android_permission_notification_required),
            getString(R.string.android_permission_miui),
            getString(R.string.android_permission_miui_enabled),
            getString(R.string.android_permission_miui_required),
            getString(R.string.android_permission_open_miui),
            getString(R.string.android_home_needs_sms_permission),
            getString(R.string.android_home_needs_inbox_permission),
            getString(R.string.android_home_needs_notification_permission),
            getString(R.string.android_home_needs_miui_permission),
            getString(R.string.android_home_needs_pairing_summary),
            getString(R.string.android_home_mac_offline_summary),
            getString(R.string.android_home_connected_summary),
            getString(R.string.android_home_unpaired_mac),
            getString(R.string.android_home_pending_count),
            getString(R.string.android_home_listening),
            getString(R.string.android_home_listener_blocked),
            getString(R.string.android_home_clear_and_pair_again)
        );
    }

    private MessageListUiState.Copy messageListCopy() {
        return new MessageListUiState.Copy(
            getString(R.string.android_message_unknown_sender),
            getString(R.string.android_message_empty_body),
            getString(R.string.android_message_forwarded),
            getString(R.string.android_message_failed),
            getString(R.string.android_message_pending),
            getString(R.string.android_message_all_forwarded),
            getString(R.string.android_message_pending_messages_title),
            getString(R.string.android_message_pending_list_count),
            getString(R.string.android_message_push_now),
            getString(R.string.android_message_retry_now),
            getString(R.string.android_message_view_messages),
            getString(R.string.android_message_error_details),
            getString(R.string.android_message_default_failure_detail)
        );
    }

    private MessageActivityUiState.Copy messageActivityCopy() {
        return new MessageActivityUiState.Copy(
            getString(R.string.android_message_recent_activity),
            getString(R.string.android_message_no_recent_activity)
        );
    }

    private ConnectionTestUiState.Copy connectionTestCopy() {
        return new ConnectionTestUiState.Copy(
            getString(R.string.android_test_button),
            getString(R.string.android_test_running_button),
            getString(R.string.android_test_running_feedback),
            getString(R.string.android_test_success_feedback),
            getString(R.string.android_test_failure_default),
            getString(R.string.android_test_retry_button),
            getString(R.string.android_test_unknown_mac)
        );
    }

    private ConnectionDetailsUiState.Copy connectionDetailsCopy() {
        return new ConnectionDetailsUiState.Copy(
            getString(R.string.android_connection_status),
            getString(R.string.android_connection_connected),
            getString(R.string.android_connection_disconnected),
            getString(R.string.android_connection_local_ip),
            getString(R.string.android_connection_no_data),
            getString(R.string.android_connection_mac_address),
            getString(R.string.android_connection_unpaired),
            getString(R.string.android_connection_background_listener),
            getString(R.string.android_connection_running),
            getString(R.string.android_connection_not_running),
            getString(R.string.android_connection_bonjour_service),
            getString(R.string.android_connection_active),
            getString(R.string.android_connection_not_found),
            getString(R.string.android_connection_device_id),
            getString(R.string.android_connection_not_generated),
            getString(R.string.android_connection_last_received),
            getString(R.string.android_connection_last_forwarded),
            getString(R.string.android_connection_app_version),
            getString(R.string.android_connection_unknown),
            getString(R.string.android_connection_transfer_method),
            getString(R.string.android_connection_secure_channel_detail),
            getString(R.string.android_connection_no_data)
        );
    }

    private ConnectionConsoleUiState.Copy connectionConsoleCopy() {
        return new ConnectionConsoleUiState.Copy(
            getString(R.string.android_console_connection_tab),
            getString(R.string.android_console_permission_tab),
            getString(R.string.android_console_queue_tab),
            getString(R.string.android_console_queue_count),
            getString(R.string.android_console_permission_limited),
            getString(R.string.android_console_permission_required),
            getString(R.string.android_console_needs_pairing),
            getString(R.string.android_console_needs_repairing),
            getString(R.string.android_console_mac_unreachable),
            getString(R.string.android_console_mac_offline_with_port),
            getString(R.string.android_console_online),
            getString(R.string.android_console_mac_online_with_port),
            getString(R.string.android_console_token_pair_again),
            getString(R.string.android_console_token_valid_less_than_hour),
            getString(R.string.android_console_token_valid_hours),
            getString(R.string.android_console_token_expired)
        );
    }

    private ForwardingStatsText.Copy forwardingStatsCopy() {
        return new ForwardingStatsText.Copy(
            getString(R.string.android_stats_pending),
            getString(R.string.android_stats_last_received),
            getString(R.string.android_stats_last_forwarded),
            getString(R.string.android_stats_failure_reason)
        );
    }

    private PairingStatusText.Copy pairingStatusCopy() {
        return new PairingStatusText.Copy(
            getString(R.string.android_status_paired_with),
            getString(R.string.android_status_discovering)
        );
    }

    private PairingFeedback.Copy pairingFeedbackCopy() {
        return new PairingFeedback.Copy(
            getString(R.string.android_pairing_invalid_code),
            getString(R.string.android_pairing_code_expired),
            getString(R.string.android_pairing_session_unavailable),
            getString(R.string.android_pairing_unable_to_connect)
        );
    }

    private String namedFormat(int resId, String name, String value) {
        return getString(resId).replace("{" + name + "}", value == null ? "" : value);
    }

    private int messageStatusColor(MessageEvent.Status status) {
        if (status == MessageEvent.Status.FORWARDED) return ConsoleTheme.ACCENT_LIME;
        if (status == MessageEvent.Status.FAILED) return ConsoleTheme.ACCENT_RED;
        return ConsoleTheme.TEXT_MUTED;
    }

    private String messageTimeText(String receivedAt) {
        if (receivedAt == null || receivedAt.isEmpty()) return "";
        try {
            ZoneId zone = ZoneId.systemDefault();
            java.time.ZonedDateTime dateTime = Instant.parse(receivedAt).atZone(zone);
            LocalDate date = dateTime.toLocalDate();
            LocalDate today = LocalDate.now(zone);
            DateTimeFormatter time = DateTimeFormatter.ofPattern("HH:mm");
            if (date.equals(today)) return namedFormat(R.string.android_message_today, "time", time.format(dateTime));
            if (date.equals(today.minusDays(1))) return namedFormat(R.string.android_message_yesterday, "time", time.format(dateTime));
            return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(dateTime);
        } catch (Exception invalid) {
            return receivedAt;
        }
    }

    private HomeUiState homeState(boolean paired, String macBaseUrl) {
        boolean miuiDevice = MiuiPermissionRequester.isMiuiDevice();
        boolean miuiAllowed = !miuiDevice || MiuiPermissionRequester.isNotificationSmsAllowed(this);
        return HomeUiState.from(
            hasReceiveSmsPermission(),
            hasReadSmsPermission(),
            hasNotificationPermission(),
            miuiDevice,
            miuiAllowed,
            paired,
            "",
            macBaseUrl,
            DeliveryWorker.pendingCount(this),
            listenerRunning,
            homeCopy()
        );
    }

    private void renderBottomNavigation() {
        boolean pairingCodeVisible = isPairingCodeVisible();
        LinearLayout wrapper = bottomNavContentInsetWrapper();
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(ConsoleTheme.dp(content, 10), ConsoleTheme.dp(content, 6), ConsoleTheme.dp(content, 10), ConsoleTheme.dp(content, 4));
        nav.setBackground(ConsoleTheme.rounded(content, ConsoleTheme.SURFACE, pairingCodeVisible ? 12 : 0, bottomNavStrokeColor(), bottomNavStrokeWidth()));
        nav.setElevation(ConsoleTheme.dp(content, pairingCodeVisible ? 4 : 0));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ConsoleTheme.dp(content, pairingCodeVisible ? 64 : 64));
        params.setMargins(0, ConsoleTheme.dp(content, pairingCodeVisible ? 0 : 0), 0, 0);
        nav.setLayoutParams(params);
        nav.addView(bottomNavButton(getString(R.string.android_nav_messages), R.drawable.ic_bridge_sms, TAB_MESSAGES), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        nav.addView(bottomNavButton(getString(R.string.android_nav_devices), R.drawable.ic_bridge_laptop, TAB_DEVICES), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        nav.addView(bottomNavButton(getString(R.string.android_nav_settings), R.drawable.ic_bridge_settings, TAB_SETTINGS), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        wrapper.addView(nav);
        bottomNavHost.addView(wrapper);
    }

    private int bottomHostHorizontalPadding(boolean whiteFlowVisible) {
        return whiteFlowVisible ? 0 : ConsoleTheme.dp(content, 28);
    }

    private int bottomHostBottomPadding(boolean whiteFlowVisible) {
        if (isPairingCodeVisible()) return Math.max(ConsoleTheme.dp(content, 8), systemBottomInset / 2);
        if (whiteFlowVisible) return 0;
        if (showsBottomNavigation()) return Math.max(ConsoleTheme.dp(content, 8), systemBottomInset / 2);
        return Math.max(ConsoleTheme.dp(content, 8), systemBottomInset);
    }

    private boolean isPairingCodeVisible() {
        return !pairingBaseUrl.isEmpty() && activeTab == TAB_DEVICES;
    }

    private LinearLayout bottomNavContentInsetWrapper() {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setBackgroundColor(ConsoleTheme.SURFACE);
        wrapper.setPadding(
            bottomHostHorizontalPadding(currentWhiteFlowVisible),
            0,
            bottomHostHorizontalPadding(currentWhiteFlowVisible),
            0
        );
        return wrapper;
    }

    private int bottomNavigationBarColor(boolean whiteFlowVisible) {
        return bottomHostBackgroundColor(whiteFlowVisible);
    }

    private int bottomHostBackgroundColor(boolean whiteFlowVisible) {
        return whiteFlowVisible || showsBottomNavigation() ? Color.WHITE : ConsoleTheme.BACKGROUND;
    }

    private int bottomNavStrokeColor() {
        return ConsoleTheme.colorWithAlpha(ConsoleTheme.STROKE, 0);
    }

    private int bottomNavStrokeWidth() {
        return 0;
    }

    private boolean usesTaskBottomSurface() {
        return activeTab == VIEW_CONNECTION_DETAILS || activeTab == VIEW_PENDING_MESSAGES;
    }

    private boolean showsBottomNavigation() {
        return !isPairingCodeVisible()
            && (activeTab == TAB_MESSAGES || activeTab == TAB_DEVICES || activeTab == TAB_SETTINGS);
    }

    private LinearLayout bottomNavButton(String text, int iconRes, int tab) {
        boolean active = activeTab == tab;
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(ConsoleTheme.dp(content, 4), 0, ConsoleTheme.dp(content, 4), 0);
        item.setBackground(ConsoleTheme.rounded(content, ConsoleTheme.colorWithAlpha(ConsoleTheme.SURFACE, 0), 12, ConsoleTheme.colorWithAlpha(ConsoleTheme.SURFACE, 0), 0));
        Motion.applyPressScale(item);

        FrameLayout iconSlot = new FrameLayout(this);
        LinearLayout.LayoutParams iconSlotParams = new LinearLayout.LayoutParams(ConsoleTheme.dp(content, 38), ConsoleTheme.dp(content, 34));
        ImageView icon = iconView(iconRes, active ? ConsoleTheme.ACCENT_TEAL : ConsoleTheme.TEXT_MUTED, 28);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        iconSlot.addView(icon, iconParams);
        item.addView(iconSlot, iconSlotParams);
        TextView label = ConsoleTheme.label(content, text, 12, active ? ConsoleTheme.ACCENT_TEAL : ConsoleTheme.TEXT_MUTED, active ? Typeface.BOLD : Typeface.NORMAL);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.setMargins(0, ConsoleTheme.dp(content, 1), 0, 0);
        item.addView(label, labelParams);

        item.setOnClickListener(v -> {
            activeTab = tab;
            pairingBaseUrl = "";
            pairingServiceName = "";
            selectedMessageDetail = null;
            refreshConnectionStatus();
        });
        return item;
    }

    private void renderStatusStrip(ConnectionConsoleUiState console, String macBaseUrl, PairingCredential credential) {
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(ConsoleTheme.dp(content, 16), ConsoleTheme.dp(content, 14), ConsoleTheme.dp(content, 16), ConsoleTheme.dp(content, 14));
        strip.setBackground(ConsoleTheme.rounded(content, ConsoleTheme.colorWithAlpha(ConsoleTheme.ACCENT_TEAL_DARK, 45), 12, ConsoleTheme.STROKE_TEAL, 1));
        LinearLayout.LayoutParams stripParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stripParams.setMargins(0, ConsoleTheme.dp(content, 4), 0, ConsoleTheme.dp(content, 14));
        strip.setLayoutParams(stripParams);

        LinearLayout dotShell = new LinearLayout(this);
        dotShell.setGravity(Gravity.CENTER);
        dotShell.setBackground(ConsoleTheme.rounded(content, ConsoleTheme.colorWithAlpha(ConsoleTheme.ACCENT_LIME, 28), 99, ConsoleTheme.colorWithAlpha(ConsoleTheme.ACCENT_LIME, 40), 1));
        LinearLayout.LayoutParams dotShellParams = new LinearLayout.LayoutParams(ConsoleTheme.dp(content, 34), ConsoleTheme.dp(content, 34));
        strip.addView(dotShell, dotShellParams);
        dotShell.addView(ConsoleTheme.statusDot(content, console.needsAttention ? ConsoleTheme.ACCENT_AMBER : ConsoleTheme.ACCENT_LIME));

        TextView title = singleLine(ConsoleTheme.label(content, console.statusTitle, 16, console.needsAttention ? ConsoleTheme.ACCENT_AMBER : ConsoleTheme.ACCENT_LIME, Typeface.BOLD));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        titleParams.setMargins(ConsoleTheme.dp(content, 12), 0, ConsoleTheme.dp(content, 10), 0);
        strip.addView(title, titleParams);

        TextView device = singleLine(ConsoleTheme.chip(content, "ID: " + shortDeviceId(credential), ConsoleTheme.ACCENT_TEAL));
        device.setOnClickListener(v -> copyText(getString(R.string.android_connection_device_id), credential == null ? "" : credential.deviceId));
        strip.addView(device);
        content.addView(strip);
    }

    private void renderConnectionTab(HomeUiState state, String macBaseUrl) {
        if (!pairingBaseUrl.isEmpty()) {
            renderPairingCodeScreen(pairingBaseUrl);
        } else {
            renderHeroTopology(state, macBaseUrl);
            renderConnectionRows(state, macBaseUrl);
            renderActionGrid(state, macBaseUrl);
            if (macBaseUrl == null || macBaseUrl.isEmpty()) renderDiscoveredMacs();
        }
    }

    private void renderHeroTopology(HomeUiState state, String macBaseUrl) {
        LinearLayout panel = ConsoleTheme.roundedPanel(content, ConsoleTheme.SURFACE, ConsoleTheme.STROKE);
        panel.setPadding(ConsoleTheme.dp(content, 14), ConsoleTheme.dp(content, 24), ConsoleTheme.dp(content, 14), ConsoleTheme.dp(content, 22));

        LinearLayout topology = new LinearLayout(this);
        topology.setOrientation(LinearLayout.HORIZONTAL);
        topology.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(topology, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        topology.addView(topologyNode(R.drawable.ic_bridge_phone, "Android", getString(R.string.android_home_current_phone), Build.MODEL, ConsoleTheme.ACCENT_TEAL), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        topology.addView(connector(true));
        topology.addView(topologyNode(R.drawable.ic_bridge_wifi, getString(R.string.android_home_local_network), localIpAddress(), "", ConsoleTheme.ACCENT_TEAL), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        topology.addView(connector(false));
        topology.addView(topologyNode(R.drawable.ic_bridge_laptop, shortMacDisplayName(macBaseUrl), "macOS", macHost(macBaseUrl), ConsoleTheme.ACCENT_LIME), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        renderMetricCardsInto(panel, macBaseUrl);
        tabContent.addView(panel);
    }

    private LinearLayout topologyNode(int iconRes, String title, String primary, String secondary, int accent) {
        LinearLayout node = new LinearLayout(this);
        node.setOrientation(LinearLayout.VERTICAL);
        node.setGravity(Gravity.CENTER);
        ImageView icon = iconView(iconRes, accent, 54);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(ConsoleTheme.dp(content, 54), ConsoleTheme.dp(content, 54));
        iconParams.gravity = Gravity.CENTER_HORIZONTAL;
        node.addView(icon, iconParams);
        TextView titleView = topologyText(title, 13, ConsoleTheme.TEXT_PRIMARY, Typeface.BOLD);
        node.addView(titleView);
        if (primary != null && !primary.isEmpty()) {
            TextView primaryView = topologyText(primary, 12, accent, Typeface.BOLD);
            LinearLayout.LayoutParams primaryParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            primaryParams.setMargins(0, ConsoleTheme.dp(content, 6), 0, 0);
            node.addView(primaryView, primaryParams);
        }
        if (secondary != null && !secondary.isEmpty()) {
            TextView secondaryView = topologyText(secondary, 11, ConsoleTheme.TEXT_SECONDARY, Typeface.NORMAL);
            LinearLayout.LayoutParams secondaryParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            secondaryParams.setMargins(0, ConsoleTheme.dp(content, 4), 0, 0);
            node.addView(secondaryView, secondaryParams);
        }
        return node;
    }

    private TextView topologyText(String value, int sp, int color, int style) {
        TextView view = singleLine(ConsoleTheme.label(content, value, sp, color, style));
        view.setGravity(Gravity.CENTER);
        int length = value == null ? 0 : value.length();
        if (length > 8) view.setTextScaleX(0.88f);
        if (length > 12) view.setTextSize(Math.max(10, sp - 1));
        if (length > 15) view.setTextScaleX(0.82f);
        return view;
    }

    private TextView connector(boolean active) {
        TextView view = ConsoleTheme.label(content, "••••", 16, active ? ConsoleTheme.ACCENT_TEAL : ConsoleTheme.TEXT_MUTED, Typeface.BOLD);
        view.setGravity(Gravity.CENTER);
        view.setMinWidth(ConsoleTheme.dp(content, 20));
        return view;
    }

    private void renderMetricCards(String macBaseUrl) {
        LinearLayout panel = ConsoleTheme.roundedPanel(content, ConsoleTheme.SURFACE, ConsoleTheme.STROKE);
        renderMetricCardsInto(panel, macBaseUrl);
        tabContent.addView(panel);
    }

    private void renderMetricCardsInto(LinearLayout parent, String macBaseUrl) {
        PairingCredential credential = new SecureTokenStore(this).loadCredential();
        boolean paired = credential != null && !credential.requiresSecureRePairing() && macBaseUrl != null && !macBaseUrl.isEmpty();
        listenerRunning = isConnectionHealthy();
        boolean miuiDevice = MiuiPermissionRequester.isMiuiDevice();
        ConnectionConsoleUiState console = ConnectionConsoleUiState.from(
            hasReceiveSmsPermission(),
            hasReadSmsPermission(),
            hasNotificationPermission(),
            miuiDevice,
            !miuiDevice || MiuiPermissionRequester.isNotificationSmsAllowed(this),
            paired,
            macBaseUrl,
            DeliveryWorker.pendingCount(this),
            listenerRunning,
            paired ? Instant.now().plusSeconds(3600) : null,
            Instant.now(),
            connectionConsoleCopy()
        );
        LinearLayout metrics = new LinearLayout(this);
        metrics.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams metricParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metricParams.setMargins(0, ConsoleTheme.dp(content, 22), 0, 0);
        parent.addView(metrics, metricParams);
        boolean bonjourFound = !discoveredServices.isEmpty();
        metrics.addView(metricCard(R.drawable.ic_bridge_target, bonjourMetricTitle(bonjourFound), bonjourMetricDetail(bonjourFound), ConsoleTheme.ACCENT_TEAL), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        metrics.addView(metricCard(R.drawable.ic_bridge_shield, paired ? getString(R.string.android_connection_secure_channel) : getString(R.string.android_connection_unpaired), paired ? getString(R.string.android_connection_secure_channel_detail) : getString(R.string.android_connection_pair_again_required), console.needsAttention ? ConsoleTheme.ACCENT_AMBER : ConsoleTheme.ACCENT_LIME), metricCardParams());
        metrics.addView(metricCard(R.drawable.ic_bridge_list, console.queueText, getString(R.string.android_connection_queue_metric_detail), ConsoleTheme.ACCENT_TEAL), metricCardParams());
    }

    private String bonjourMetricTitle(boolean found) {
        return "Bonjour";
    }

    private String bonjourMetricDetail(boolean found) {
        return found ? "_smspusher" : getString(R.string.android_connection_not_found);
    }

    private LinearLayout.LayoutParams metricCardParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        params.setMargins(ConsoleTheme.dp(content, 8), 0, 0, 0);
        return params;
    }

    private LinearLayout metricCard(int iconRes, String title, String detail, int accent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(ConsoleTheme.dp(content, 8), ConsoleTheme.dp(content, 12), ConsoleTheme.dp(content, 8), ConsoleTheme.dp(content, 12));
        card.setBackground(ConsoleTheme.rounded(content, ConsoleTheme.colorWithAlpha(ConsoleTheme.SURFACE_ALT, 190), 10, ConsoleTheme.colorWithAlpha(accent, 65), 1));
        card.addView(iconView(iconRes, accent, 22));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        textParams.setMargins(ConsoleTheme.dp(content, 6), 0, 0, 0);
        text.addView(metricText(title, 11, accent, Typeface.BOLD));
        TextView detailView = metricText(detail, 10, ConsoleTheme.TEXT_SECONDARY, Typeface.NORMAL);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailParams.setMargins(0, ConsoleTheme.dp(content, 5), 0, 0);
        text.addView(detailView, detailParams);
        card.addView(text, textParams);
        return card;
    }

    private TextView metricText(String value, int sp, int color, int style) {
        TextView view = singleLine(ConsoleTheme.label(content, value, sp, color, style));
        int length = value == null ? 0 : value.length();
        if (length > 8) view.setTextScaleX(0.86f);
        if (length > 12) view.setTextScaleX(0.78f);
        return view;
    }

    private void renderConnectionRows(HomeUiState state, String macBaseUrl) {
        LinearLayout rows = ConsoleTheme.roundedPanel(content, ConsoleTheme.SURFACE, ConsoleTheme.STROKE);
        rows.setPadding(0, 0, 0, 0);
        String pairedStatus = pairedMacStatusText(macBaseUrl);
        addConnectionRow(rows, R.drawable.ic_bridge_monitor, getString(R.string.android_connection_paired_mac), pairedMacSubtitle(macBaseUrl), pairedStatus, pairedMacStatusColor(pairedStatus), v -> {
            if (macBaseUrl != null && !macBaseUrl.isEmpty()) testConnection();
        });
        rows.addView(ConsoleTheme.divider(content));
        addConnectionRow(rows, R.drawable.ic_bridge_globe, getString(R.string.android_connection_manual_pair_device), getString(R.string.android_connection_manual_pair_subtitle), "›", ConsoleTheme.TEXT_SECONDARY, v -> {
            activeTab = VIEW_MANUAL_PAIRING;
            sectionError.setVisibility(View.GONE);
            refreshConnectionStatus();
        });
        rows.addView(ConsoleTheme.divider(content));
        ForwardingStatsText stats = ForwardingStatsStore.text(this, DeliveryWorker.pendingCount(this), forwardingStatsCopy());
        addConnectionRow(rows, R.drawable.ic_bridge_sms, getString(R.string.android_connection_last_received_sms), lastReceivedValue(stats), "", ConsoleTheme.ACCENT_LIME, null);
        rows.addView(ConsoleTheme.divider(content));
        addConnectionRow(rows, R.drawable.ic_bridge_bell, getString(R.string.android_connection_notification_listener), listenerSubtitle(), listenerRunning ? getString(R.string.android_connection_running) : state.listenerText, listenerRunning ? ConsoleTheme.ACCENT_LIME : ConsoleTheme.ACCENT_AMBER, v -> updateStatusFromStoredPairing());
        tabContent.addView(rows);
    }

    private String pairedMacStatusText(String macBaseUrl) {
        if (macBaseUrl == null || macBaseUrl.isEmpty()) return getString(R.string.android_connection_unpaired);
        return listenerRunning ? getString(R.string.android_connection_online) : getString(R.string.android_connection_offline);
    }

    private int pairedMacStatusColor(String statusText) {
        if (getString(R.string.android_connection_online).equals(statusText)) return ConsoleTheme.ACCENT_LIME;
        if (getString(R.string.android_connection_offline).equals(statusText)) return ConsoleTheme.ACCENT_AMBER;
        return ConsoleTheme.TEXT_SECONDARY;
    }

    private String listenerSubtitle() {
        if (!hasNotificationPermission()) return getString(R.string.android_connection_notification_permission_needed);
        return listenerRunning ? getString(R.string.android_connection_authorized_running) : getString(R.string.android_connection_authorized_waiting);
    }

    private void addConnectionRow(LinearLayout rows, int iconRes, String title, String subtitle, String statusText, int statusColor, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ConsoleTheme.dp(content, 18), ConsoleTheme.dp(content, 17), ConsoleTheme.dp(content, 18), ConsoleTheme.dp(content, 17));
        if (listener != null) row.setOnClickListener(listener);

        LinearLayout iconFrame = new LinearLayout(this);
        iconFrame.setGravity(Gravity.CENTER);
        iconFrame.addView(iconView(iconRes, ConsoleTheme.ACCENT_TEAL, 34));
        row.addView(iconFrame, new LinearLayout.LayoutParams(ConsoleTheme.dp(content, 48), ConsoleTheme.dp(content, 48)));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        textParams.setMargins(ConsoleTheme.dp(content, 12), 0, ConsoleTheme.dp(content, 10), 0);
        text.addView(singleLine(ConsoleTheme.label(content, title, 16, ConsoleTheme.TEXT_PRIMARY, Typeface.BOLD)));
        TextView subtitleView = ConsoleTheme.label(content, subtitle == null ? "" : subtitle, 13, ConsoleTheme.TEXT_SECONDARY, Typeface.NORMAL);
        subtitleView.setMaxLines(2);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleParams.setMargins(0, ConsoleTheme.dp(content, 6), 0, 0);
        text.addView(subtitleView, subtitleParams);
        row.addView(text, textParams);

        if (statusText != null && !statusText.isEmpty()) {
            TextView statusView = singleLine(ConsoleTheme.label(content, statusText, 14, statusColor, Typeface.BOLD));
            statusView.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
            row.addView(statusView);
        }
        rows.addView(row);
    }

    private void renderActionGrid(HomeUiState state, String macBaseUrl) {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, ConsoleTheme.dp(content, 12), 0, 0);
        tabContent.addView(grid, params);
        grid.addView(actionTile(R.drawable.ic_bridge_refresh, getString(R.string.android_connection_refresh), false, v -> updateStatusFromStoredPairing()), new LinearLayout.LayoutParams(0, ConsoleTheme.dp(content, 82), 1));
        grid.addView(actionTile(R.drawable.ic_bridge_clipboard, getString(R.string.android_connection_copy_diagnostics), false, v -> copyDiagnostics()), actionTileParams());
        Button test = actionTile(R.drawable.ic_bridge_pulse, connectionTestUiState.buttonLabel, false, v -> testConnection());
        test.setEnabled(connectionTestUiState.buttonEnabled);
        test.setAlpha(connectionTestUiState.buttonEnabled ? 1f : 0.62f);
        grid.addView(test, actionTileParams());
        Button rePair = actionTile(R.drawable.ic_bridge_link, state.rePairActionLabel, true, v -> clearStoredPairingForDeviceSelection());
        rePair.setEnabled(state.rePairActionEnabled);
        grid.addView(rePair, actionTileParams());
    }

    private LinearLayout.LayoutParams actionTileParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ConsoleTheme.dp(content, 82), 1);
        params.setMargins(ConsoleTheme.dp(content, 10), 0, 0, 0);
        return params;
    }

    private Button actionTile(int iconRes, String text, boolean primary, View.OnClickListener listener) {
        Button button = ConsoleTheme.roundedButton(content, text, primary);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(14);
        button.setCompoundDrawablesWithIntrinsicBounds(0, iconRes, 0, 0);
        button.setCompoundDrawableTintList(ColorStateList.valueOf(primary ? ConsoleTheme.TEXT_PRIMARY : ConsoleTheme.ACCENT_TEAL));
        button.setCompoundDrawablePadding(ConsoleTheme.dp(content, 6));
        button.setOnClickListener(listener);
        Motion.applyPressScale(button);
        return button;
    }

    private ImageView iconView(int drawableRes, int color, int sizeDp) {
        ImageView image = new ImageView(this);
        image.setImageResource(drawableRes);
        image.setColorFilter(color);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int inset = ConsoleTheme.dp(content, 2);
        image.setPadding(inset, inset, inset, inset);
        image.setLayoutParams(new LinearLayout.LayoutParams(
            ConsoleTheme.dp(content, sizeDp),
            ConsoleTheme.dp(content, sizeDp)
        ));
        return image;
    }

    private LinearLayout iconBadge(int drawableRes, int color, int sizeDp) {
        LinearLayout badge = new LinearLayout(this);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(ConsoleTheme.rounded(content, ConsoleTheme.colorWithAlpha(color, 26), 99, ConsoleTheme.colorWithAlpha(color, 0), 0));
        badge.addView(iconView(drawableRes, color, Math.max(20, sizeDp / 2)));
        return badge;
    }

    private void addMessageDetailRow(LinearLayout parent, String labelText, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ConsoleTheme.dp(content, 16), ConsoleTheme.dp(content, 15), ConsoleTheme.dp(content, 16), ConsoleTheme.dp(content, 15));
        TextView label = ConsoleTheme.label(content, labelText, 14, ConsoleTheme.TEXT_SECONDARY, Typeface.NORMAL);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView valueView = ConsoleTheme.label(content, value == null || value.isEmpty() ? "-" : value, 14, Color.rgb(26, 27, 31), Typeface.BOLD);
        valueView.setGravity(Gravity.RIGHT);
        valueView.setMaxLines(2);
        valueView.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(valueView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        parent.addView(row);
    }

    private String shortMessageId(String messageId) {
        if (messageId == null || messageId.isEmpty()) return "-";
        String sanitized = messageId.replaceAll("[^A-Za-z0-9]", "");
        if (sanitized.length() <= 10) return sanitized;
        return sanitized.substring(0, 5) + "..." + sanitized.substring(sanitized.length() - 5);
    }

    private TextView singleLine(TextView view) {
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    private String pairedMacSubtitle(String macBaseUrl) {
        if (macBaseUrl == null || macBaseUrl.isEmpty()) return getString(R.string.android_connection_unpaired);
        return macDisplayName(macBaseUrl) + " (" + shortDeviceId(new SecureTokenStore(this).loadCredential()) + ")\n" + macHost(macBaseUrl) + ":" + macPort(macBaseUrl);
    }

    private String lastReceivedValue(ForwardingStatsText stats) {
        String line = stats == null ? "" : stats.lastReceivedLine;
        String prefix = getString(R.string.android_stats_last_received).replace("{value}", "");
        if (line == null || line.isEmpty() || line.equals(prefix + "-")) return getString(R.string.android_connection_no_record);
        return line.startsWith(prefix) ? line.substring(prefix.length()) : line;
    }

    private String shortDeviceId(PairingCredential credential) {
        if (credential == null || credential.deviceId == null || credential.deviceId.isEmpty()) return "--";
        String sanitized = credential.deviceId.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        if (sanitized.length() >= 8) {
            String tail = sanitized.substring(sanitized.length() - 8);
            return tail.substring(0, 4) + "-" + tail.substring(4);
        }
        return credential.deviceId;
    }

    private String secureAuthText(PairingCredential credential) {
        if (credential == null || credential.requiresSecureRePairing()) return getString(R.string.android_connection_secure_pairing_incomplete);
        return getString(R.string.android_connection_secure_channel_detail);
    }

    private String appVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException missing) {
            return getString(R.string.android_connection_unknown);
        }
    }

    private String macDisplayName(String macBaseUrl) {
        for (NsdServiceInfo service : discoveredServices) {
            String candidate = MacEndpointUrls.baseUrl(service);
            if (macBaseUrl != null && macBaseUrl.equals(candidate)) return service.getServiceName();
        }
        PairingEndpoint endpoint = PairingStore.loadEndpoint(this);
        if (macBaseUrl != null && macBaseUrl.equals(endpoint.baseUrl) && !endpoint.serviceName.isEmpty()) {
            return endpoint.serviceName;
        }
        PairingCredential credential = new SecureTokenStore(this).loadCredential();
        if (credential != null && !credential.pairedDesktopName.isEmpty()) return credential.pairedDesktopName;
        return getString(R.string.android_connection_computer);
    }

    private String shortMacDisplayName(String macBaseUrl) {
        return macDisplayName(macBaseUrl);
    }

    private String macHost(String macBaseUrl) {
        try {
            URI uri = URI.create(macBaseUrl);
            return uri.getHost() == null ? "--" : uri.getHost();
        } catch (Exception invalid) {
            return "--";
        }
    }

    private String macPort(String macBaseUrl) {
        try {
            URI uri = URI.create(macBaseUrl);
            return uri.getPort() > 0 ? String.valueOf(uri.getPort()) : "55515";
        } catch (Exception invalid) {
            return "55515";
        }
    }

    private String localIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            String fallback = "";
            for (NetworkInterface networkInterface : interfaces) {
                if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;
                for (java.net.InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) continue;
                    String value = address.getHostAddress();
                    if (networkInterface.getName().startsWith("wlan")) return value;
                    if (fallback.isEmpty()) fallback = value;
                }
            }
            return fallback.isEmpty() ? "--" : fallback;
        } catch (Exception ignored) {
            return "--";
        }
    }

    private void copyText(String label, String value) {
        if (value == null || value.isEmpty()) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
        Toast.makeText(this, namedFormat(R.string.android_toast_copied, "label", label), Toast.LENGTH_SHORT).show();
    }

    private void copyDiagnostics() {
        PairingCredential credential = new SecureTokenStore(this).loadCredential();
        String macBaseUrl = PairingStore.loadMacBaseUrl(this);
        ForwardingStatsText stats = ForwardingStatsStore.text(this, DeliveryWorker.pendingCount(this), forwardingStatsCopy());
        String diagnostics = "SmsPusher\n"
            + "deviceId=" + (credential == null ? "" : credential.deviceId) + "\n"
            + "protocolVersion=" + (credential == null ? "" : credential.protocolVersion) + "\n"
            + "macBaseUrl=" + macBaseUrl + "\n"
            + "listenerRunning=" + listenerRunning + "\n"
            + "pending=" + DeliveryWorker.pendingCount(this) + "\n"
            + stats.lastReceivedLine + "\n"
            + stats.lastForwardedLine + "\n"
            + stats.lastFailureLine;
        String recentLogs = DiagnosticLogExporter.recentLogText(this);
        if (!recentLogs.isEmpty()) diagnostics += "\n\nRecent logs:\n" + recentLogs;
        copyText(getString(R.string.android_connection_diagnostics), diagnostics);
    }

    private void renderPermissionTab(HomeUiState state) {
        boolean backgroundLimited = !isIgnoringBatteryOptimizations();
        if (backgroundLimited) renderPermissionWarningCard();

        tabContent.addView(ConsoleTheme.label(content, getString(R.string.android_permission_system), 16, ConsoleTheme.TEXT_SECONDARY, Typeface.BOLD));
        LinearLayout permissions = ConsoleTheme.panel(content);
        permissions.setPadding(0, 0, 0, 0);
        addPermissionToggleRow(permissions, R.drawable.ic_bridge_sms, state.smsPermission, v -> requestRuntimePermissionFromToggle(Manifest.permission.RECEIVE_SMS));
        permissions.addView(ConsoleTheme.divider(content));
        addPermissionToggleRow(permissions, R.drawable.ic_bridge_list, state.inboxPermission, v -> requestRuntimePermissionFromToggle(Manifest.permission.READ_SMS));
        permissions.addView(ConsoleTheme.divider(content));
        addPermissionToggleRow(permissions, R.drawable.ic_bridge_bell, state.notificationPermission, v -> requestRuntimePermissionFromToggle(Manifest.permission.POST_NOTIFICATIONS));
        if (state.showMiuiPermission) {
            permissions.addView(ConsoleTheme.divider(content));
            addPermissionToggleRow(permissions, R.drawable.ic_bridge_shield, state.miuiPermission, v -> MiuiPermissionRequester.openPermissionSettings(this));
        }
        permissions.addView(ConsoleTheme.divider(content));
        addPermissionServiceRow(permissions, backgroundLimited);
        permissions.addView(ConsoleTheme.divider(content));
        addLanguageSettingsRow(permissions);
        tabContent.addView(permissions);
    }

    private void renderPermissionWarningCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackground(ConsoleTheme.rounded(content, ConsoleTheme.SURFACE, 18, ConsoleTheme.colorWithAlpha(ConsoleTheme.STROKE, 35), 1));
        card.setElevation(ConsoleTheme.dp(content, 8));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, ConsoleTheme.dp(content, 8), 0, ConsoleTheme.dp(content, 30));
        tabContent.addView(card, cardParams);

        View rail = new View(this);
        rail.setBackgroundColor(Color.rgb(255, 178, 100));
        card.addView(rail, new LinearLayout.LayoutParams(ConsoleTheme.dp(content, 4), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setGravity(Gravity.TOP);
        body.setPadding(ConsoleTheme.dp(content, 16), ConsoleTheme.dp(content, 20), ConsoleTheme.dp(content, 18), ConsoleTheme.dp(content, 18));
        card.addView(body, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        body.addView(iconBadge(R.drawable.ic_bridge_pulse, Color.rgb(198, 111, 0), 42), new LinearLayout.LayoutParams(ConsoleTheme.dp(content, 42), ConsoleTheme.dp(content, 42)));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        textParams.setMargins(ConsoleTheme.dp(content, 14), 0, 0, 0);
        text.addView(ConsoleTheme.label(content, getString(R.string.android_permission_background_paused_title), 22, ConsoleTheme.TEXT_PRIMARY, Typeface.NORMAL));
        TextView description = ConsoleTheme.label(content, getString(R.string.android_permission_background_paused_description), 15, ConsoleTheme.TEXT_SECONDARY, Typeface.NORMAL);
        description.setSingleLine(false);
        LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descParams.setMargins(0, ConsoleTheme.dp(content, 8), 0, 0);
        text.addView(description, descParams);

        Button action = ConsoleTheme.roundedButton(content, getString(R.string.android_permission_fix_issue), true);
        action.setTextSize(14);
        action.setBackground(ConsoleTheme.rounded(content, Color.rgb(174, 96, 0), 18, Color.rgb(174, 96, 0), 0));
        action.setOnClickListener(v -> requestBatteryOptimizationExemption());
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(ConsoleTheme.dp(content, 100), ConsoleTheme.dp(content, 38));
        actionParams.setMargins(0, ConsoleTheme.dp(content, 20), 0, 0);
        text.addView(action, actionParams);
        body.addView(text, textParams);
    }

    private void addPermissionServiceRow(LinearLayout rows, boolean needsRepair) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(ConsoleTheme.dp(content, 18), ConsoleTheme.dp(content, 17), ConsoleTheme.dp(content, 18), ConsoleTheme.dp(content, 17));

        int accent = needsRepair ? ConsoleTheme.ACCENT_RED : ConsoleTheme.ACCENT_TEAL;
        item.addView(iconBadge(R.drawable.ic_bridge_pulse, accent, 48), new LinearLayout.LayoutParams(ConsoleTheme.dp(content, 48), ConsoleTheme.dp(content, 48)));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        textParams.setMargins(ConsoleTheme.dp(content, 14), 0, ConsoleTheme.dp(content, 12), 0);
        text.addView(settingsRowTitle(getString(R.string.android_permission_foreground_service), 17, Typeface.NORMAL));
        TextView description = ConsoleTheme.label(content, needsRepair ? getString(R.string.android_permission_battery_limited_description) : serviceHeartbeatText(), 13, ConsoleTheme.TEXT_SECONDARY, Typeface.NORMAL);
        description.setMaxLines(2);
        description.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descriptionParams.setMargins(0, ConsoleTheme.dp(content, 5), 0, 0);
        text.addView(description, descriptionParams);
        item.addView(text, textParams);

        if (needsRepair) {
            Button repair = ConsoleTheme.roundedButton(content, getString(R.string.android_permission_repair), true);
            repair.setTextSize(13);
            repair.setBackground(ConsoleTheme.rounded(content, ConsoleTheme.ACCENT_TEAL_DARK, 18, ConsoleTheme.ACCENT_TEAL_DARK, 0));
            repair.setOnClickListener(v -> requestBatteryOptimizationExemption());
            item.addView(repair, new LinearLayout.LayoutParams(ConsoleTheme.dp(content, 76), ConsoleTheme.dp(content, 38)));
        } else {
            item.addView(statusPill(getString(R.string.android_permission_enabled), ConsoleTheme.ACCENT_LIME));
        }
        rows.addView(item);
    }

    private void addLanguageSettingsRow(LinearLayout rows) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(ConsoleTheme.dp(content, 18), ConsoleTheme.dp(content, 17), ConsoleTheme.dp(content, 18), ConsoleTheme.dp(content, 17));
        item.addView(iconBadge(R.drawable.ic_bridge_settings, ConsoleTheme.ACCENT_TEAL, 48), new LinearLayout.LayoutParams(ConsoleTheme.dp(content, 48), ConsoleTheme.dp(content, 48)));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        textParams.setMargins(ConsoleTheme.dp(content, 14), 0, ConsoleTheme.dp(content, 12), 0);
        text.addView(singleLine(ConsoleTheme.label(content, getString(R.string.common_language_title), 17, ConsoleTheme.TEXT_PRIMARY, Typeface.NORMAL)));
        text.addView(singleLine(ConsoleTheme.label(content, languagePreferenceLabel(LanguagePreferenceStore.get(this)), 13, ConsoleTheme.TEXT_SECONDARY, Typeface.NORMAL)));
        item.addView(text, textParams);
        item.addView(settingsLanguageChevron(), new LinearLayout.LayoutParams(ConsoleTheme.dp(content, 18), ViewGroup.LayoutParams.WRAP_CONTENT));
        item.setOnClickListener(v -> showLanguagePicker());
        rows.addView(item);
    }

    private TextView settingsLanguageChevron() {
        TextView arrow = ConsoleTheme.label(content, "›", 24, ConsoleTheme.TEXT_MUTED, Typeface.NORMAL);
        arrow.setGravity(Gravity.CENTER);
        return arrow;
    }

    private String languagePreferenceLabel(LanguagePreference preference) {
        if (preference == LanguagePreference.ZH_CN) return getString(R.string.common_language_zh_cn);
        if (preference == LanguagePreference.EN_US) return getString(R.string.common_language_en_us);
        return getString(R.string.common_language_auto);
    }

    private void showLanguagePicker() {
        LanguagePreference current = LanguagePreferenceStore.get(this);
        LanguagePreference[] values = new LanguagePreference[] {
            LanguagePreference.AUTO,
            LanguagePreference.ZH_CN,
            LanguagePreference.EN_US
        };
        String[] labels = new String[] {
            getString(R.string.common_language_auto),
            getString(R.string.common_language_zh_cn),
            getString(R.string.common_language_en_us)
        };
        int checked = current == LanguagePreference.ZH_CN ? 1 : current == LanguagePreference.EN_US ? 2 : 0;
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.common_language_title))
            .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                LanguagePreference preference = values[which];
                LanguagePreferenceStore.set(this, preference);
                dialog.dismiss();
                recreate();
            })
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show();
    }

    private String batteryOptimizationDescription() {
        if (isIgnoringBatteryOptimizations()) return getString(R.string.android_permission_battery_allowed_description);
        return getString(R.string.android_permission_battery_blocked_description);
    }

    private String serviceHeartbeatText() {
        long lastHeartbeat = ServiceHealthStore.lastHeartbeatAt(this);
        if (lastHeartbeat <= 0L) return getString(R.string.android_permission_no_heartbeat);
        long ageSeconds = Math.max(0L, (System.currentTimeMillis() - lastHeartbeat) / 1000L);
        return namedFormat(R.string.android_permission_last_heartbeat_seconds, "seconds", String.valueOf(ageSeconds));
    }

    private void renderQueueTab(String macBaseUrl) {
        int pending = DeliveryWorker.pendingCount(this);
        ForwardingStatsText stats = ForwardingStatsStore.text(this, pending, forwardingStatsCopy());
        LinearLayout panel = ConsoleTheme.panel(content);
        panel.addView(ConsoleTheme.label(content, stats.pendingLine, 16, ConsoleTheme.TEXT_PRIMARY, Typeface.BOLD));
        panel.addView(ConsoleTheme.label(content, stats.lastReceivedLine, 13, ConsoleTheme.TEXT_SECONDARY, Typeface.NORMAL));
        panel.addView(ConsoleTheme.label(content, stats.lastForwardedLine, 13, ConsoleTheme.TEXT_SECONDARY, Typeface.NORMAL));
        panel.addView(ConsoleTheme.label(content, stats.lastFailureLine, 13, ConsoleTheme.TEXT_SECONDARY, Typeface.NORMAL));

        Button retry = ConsoleTheme.action(content, getString(R.string.android_queue_retry_now));
        retry.setEnabled(macBaseUrl != null && !macBaseUrl.isEmpty() && pending > 0);
        retry.setOnClickListener(v -> DeliveryWorker.drainAsync(this, macBaseUrl));
        panel.addView(retry);

        Button clear = ConsoleTheme.action(content, getString(R.string.android_queue_clear_failed));
        clear.setEnabled(pending > 0);
        clear.setOnClickListener(v -> confirmClearQueue());
        panel.addView(clear);
        tabContent.addView(panel);
    }

    private void confirmClearQueue() {
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.android_queue_clear_failed_title))
            .setMessage(getString(R.string.android_queue_clear_failed_message))
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.android_queue_clear), (dialog, which) -> {
                DeliveryWorker.clearPending(this);
                renderHome();
            })
            .show();
    }

    private void addPermissionToggleRow(LinearLayout rows, int iconRes, HomeUiState.PermissionRow row, View.OnClickListener action) {
        PermissionToggleUiState toggleState = PermissionToggleUiState.runtime(row.title, row.actionLabel.isEmpty(), row.actionLabel, getString(R.string.android_permission_enable));
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(ConsoleTheme.dp(content, 18), ConsoleTheme.dp(content, 17), ConsoleTheme.dp(content, 18), ConsoleTheme.dp(content, 17));

        LinearLayout iconFrame = new LinearLayout(this);
        iconFrame.setGravity(Gravity.CENTER);
        int accent = toggleState.checked ? ConsoleTheme.ACCENT_TEAL : ConsoleTheme.TEXT_MUTED;
        iconFrame.setBackground(ConsoleTheme.rounded(content, ConsoleTheme.colorWithAlpha(accent, 24), 99, ConsoleTheme.colorWithAlpha(accent, 0), 0));
        iconFrame.addView(iconView(iconRes, accent, 28));
        item.addView(iconFrame, new LinearLayout.LayoutParams(ConsoleTheme.dp(content, 48), ConsoleTheme.dp(content, 48)));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        textParams.setMargins(ConsoleTheme.dp(content, 14), 0, ConsoleTheme.dp(content, 12), 0);
        text.addView(settingsRowTitle(row.title, 17, Typeface.BOLD));
        TextView description = ConsoleTheme.label(content, row.description, 13, ConsoleTheme.TEXT_SECONDARY, Typeface.NORMAL);
        description.setMaxLines(2);
        description.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descriptionParams.setMargins(0, ConsoleTheme.dp(content, 5), 0, 0);
        text.addView(description, descriptionParams);
        item.addView(text, textParams);

        Switch toggle = new Switch(this);
        toggle.setShowText(false);
        toggle.setText("");
        toggle.setChecked(toggleState.checked);
        toggle.setEnabled(true);
        toggle.setClickable(toggleState.actionEnabled);
        toggle.setFocusable(toggleState.actionEnabled);
        toggle.setMinWidth(ConsoleTheme.dp(content, 52));
        toggle.setOnCheckedChangeListener((buttonView, checked) -> {
            if (!checked) return;
            if (!toggleState.actionEnabled || action == null) {
                buttonView.setChecked(true);
                return;
            }
            action.onClick(buttonView);
            buttonView.post(() -> ((Switch) buttonView).setChecked(false));
        });
        item.addView(toggle);
        if (toggleState.actionEnabled) {
            item.setOnClickListener(v -> toggle.performClick());
        }
        rows.addView(item);
    }

    private TextView settingsRowTitle(String text, int sp, int style) {
        TextView title = ConsoleTheme.label(content, text, sp, ConsoleTheme.TEXT_PRIMARY, style);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        return title;
    }

    private void renderDiscoveredMacs() {
        LinearLayout panel = ConsoleTheme.panel(content);
        panel.addView(ConsoleTheme.label(content, getString(R.string.android_discovery_discovered_macs), 14, ConsoleTheme.TEXT_SECONDARY, Typeface.BOLD));
        int rendered = 0;
        for (NsdServiceInfo service : discoveredServices) {
            String baseUrl = MacEndpointUrls.baseUrl(service);
            if (baseUrl == null) continue;
            String hostAddress = MacEndpointUrls.displayAddress(service);
            TextView label = ConsoleTheme.label(content, service.getServiceName() + " · " + hostAddress, 14, ConsoleTheme.TEXT_PRIMARY, Typeface.NORMAL);
            Button pair = ConsoleTheme.action(content, getString(R.string.android_discovery_pair));
            pair.setOnClickListener(v -> showPairingForm(endpointFromService(service, baseUrl)));
            panel.addView(label);
            panel.addView(pair);
            rendered++;
        }
        if (rendered == 0) {
            panel.addView(ConsoleTheme.label(content, getString(R.string.android_discovery_no_macs_manual), 13, ConsoleTheme.TEXT_SECONDARY, Typeface.NORMAL));
        }
        tabContent.addView(panel);
    }

    private void addSectionTitle(String title) {
        TextView view = new TextView(this);
        view.setText(title);
        view.setTextSize(16);
        content.addView(view);
    }

    private void addInfoText(String value) {
        TextView view = new TextView(this);
        view.setText(value);
        content.addView(view);
    }

    private void addPermissionRow(HomeUiState.PermissionRow row, View.OnClickListener action) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        TextView text = new TextView(this);
        text.setText(row.title + ": " + row.status + "\n" + row.description);
        wrapper.addView(text);
        if (!row.actionLabel.isEmpty()) {
            Button button = new Button(this);
            button.setText(row.actionLabel);
            button.setOnClickListener(action);
            wrapper.addView(button);
        }
        content.addView(wrapper);
    }

    private void requestRequiredPermissionsIfNeeded() {
        List<String> missingPermissions = new ArrayList<>();
        if (!hasReceiveSmsPermission()) {
            missingPermissions.add(Manifest.permission.RECEIVE_SMS);
        }
        if (!hasReadSmsPermission()) {
            missingPermissions.add(Manifest.permission.READ_SMS);
        }
        if (!hasNotificationPermission()) {
            missingPermissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (missingPermissions.isEmpty()) return;
        status.setText(permissionStatus(missingPermissions));
        requestRuntimePermissions(missingPermissions.toArray(new String[0]));
    }

    private void requestRuntimePermissionFromToggle(String permission) {
        if (permission == null || permission.isEmpty()) return;
        RuntimePermissionRequestPolicy.Action action = RuntimePermissionRequestPolicy.actionForToggle();
        if (action != RuntimePermissionRequestPolicy.Action.REQUEST_RUNTIME_PERMISSION) return;
        requestRuntimePermissions(new String[] { permission });
    }

    private void requestRuntimePermissions(String[] permissions) {
        if (permissions == null || permissions.length == 0) return;
        requestPermissions(permissions, RUNTIME_PERMISSION_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == RUNTIME_PERMISSION_REQUEST) refreshPermissionStateFromSystem();
    }

    private void updateStatusFromStoredPairing() {
        PairingCredential credential = new SecureTokenStore(this).loadCredential();
        String macBaseUrl = PairingStore.loadMacBaseUrl(this);
        boolean paired = credential != null && !credential.requiresSecureRePairing();
        LOG.info("pairing status loaded credentialPresent={} macBaseUrl={}", paired, macBaseUrl);
        String statusText = PairingStatusText.from(paired, macBaseUrl, pairingStatusCopy());
        if (paired && !macBaseUrl.isEmpty()) {
            if (hasNotificationPermission()) {
                listenerRunning = false;
                statusText += "\n" + getString(R.string.android_status_verifying_mac);
                verifyStoredPairingAsync(credential, macBaseUrl);
            } else {
                listenerRunning = false;
                statusText += "\n" + getString(R.string.android_status_notification_needed);
            }
        } else if (paired) {
            listenerRunning = false;
            SmsListenerService.stop(this);
            statusText += "\n" + getString(R.string.android_status_discovering_paired_mac);
            recoverMissingEndpointAsync(credential);
        } else {
            listenerRunning = false;
            SmsListenerService.stop(this);
        }
        status.setText(statusText);
        renderHome();
        MiuiPermissionRequester.promptIfNeeded(this);
    }

    private void recoverMissingEndpointAsync(PairingCredential credential) {
        if (credential == null || endpointRecoveryInFlight) return;
        endpointRecoveryInFlight = true;
        PairingEndpoint seed = new PairingEndpoint(
            "",
            "",
            PairingEndpoint.SERVICE_TYPE,
            PairingEndpoint.DEFAULT_PORT,
            "v2",
            "",
            ""
        );
        new Thread(() -> {
            try {
                LOG.info("endpoint recovery start pairedDesktopName={}", credential.pairedDesktopName);
                reserveCredentialCounter(credential);
                MacEndpointResolution resolution = new NsdMacEndpointResolver(this).resolve(seed, credential);
                if (resolution != null && resolution.isUsable()) {
                    PairingStore.saveEndpoint(this, resolution.endpoint);
                    new SecureTokenStore(this).saveCredential(resolution.credential);
                    LOG.info("endpoint recovery success baseUrl={}", resolution.endpoint.baseUrl);
                    runOnUiThread(() -> {
                        endpointRecoveryInFlight = false;
                        activateVerifiedPairing(resolution.endpoint.baseUrl);
                    });
                    return;
                }
                LOG.warn("endpoint recovery failed pairedDesktopName={}", credential.pairedDesktopName);
                runOnUiThread(() -> {
                    endpointRecoveryInFlight = false;
                    renderHome();
                });
            } catch (SmsBridgeClient.PairingRequiredException revoked) {
                LOG.warn("endpoint recovery revoked reason={}", revoked.reason);
                runOnUiThread(() -> {
                    endpointRecoveryInFlight = false;
                    clearStoredPairing(getString(R.string.android_toast_revoked));
                });
            } catch (Exception error) {
                LOG.error("endpoint recovery failed pairedDesktopName={}", credential.pairedDesktopName, error);
                runOnUiThread(() -> {
                    endpointRecoveryInFlight = false;
                    renderHome();
                });
            }
        }, "SmsBridgeEndpointRecovery").start();
    }

    private void verifyStoredPairingAsync(PairingCredential credential, String macBaseUrl) {
        if (credential == null) {
            LOG.info("pairing verification skipped reason=missing_credential baseUrl={}", macBaseUrl);
            return;
        }
        if (!pairingVerificationInFlight.compareAndSet(false, true)) {
            LOG.info("pairing verification skipped reason=in_flight baseUrl={} deviceId={}", macBaseUrl, credential.deviceId);
            return;
        }
        new Thread(() -> {
            try {
                LOG.info("pairing verification start baseUrl={} deviceId={}", macBaseUrl, credential.deviceId);
                SmsBridgeClient client = new SmsBridgeClient(macBaseUrl, new UrlConnectionTransport());
                reserveCredentialCounter(credential);
                PairingCredential verified = client.verifyPairing(credential);
                new SecureTokenStore(this).saveCredential(verified);
                runOnUiThread(() -> {
                    pairingVerificationInFlight.set(false);
                    activateVerifiedPairing(macBaseUrl);
                });
            } catch (SmsBridgeClient.PairingRequiredException revoked) {
                LOG.warn("pairing verification revoked reason={}", revoked.reason);
                runOnUiThread(() -> {
                    pairingVerificationInFlight.set(false);
                    clearStoredPairing(getString(R.string.android_toast_revoked));
                });
            } catch (Exception error) {
                LOG.error("pairing verification failed baseUrl={}", macBaseUrl, error);
                runOnUiThread(() -> {
                    pairingVerificationInFlight.set(false);
                    listenerRunning = false;
                    ServiceHealthStore.recordNetworkLost(this);
                    showStatusToast(namedFormat(R.string.android_toast_verify_failed, "reason", error.getClass().getSimpleName()));
                    recoverEndpointAfterVerificationFailure(credential);
                    refreshConnectionStatus();
                });
            }
        }, "SmsBridgePairingVerify").start();
    }

    private void recoverEndpointAfterVerificationFailure(PairingCredential fallbackCredential) {
        PairingCredential latest = new SecureTokenStore(this).loadCredential();
        PairingCredential recoveryCredential = latest == null ? fallbackCredential : latest;
        if (recoveryCredential == null || recoveryCredential.requiresSecureRePairing()) return;
        recoverMissingEndpointAsync(recoveryCredential);
    }

    private void activateVerifiedPairing(String macBaseUrl) {
        try {
            lastPairingVerifiedAtMillis = SystemClock.elapsedRealtime();
            SmsListenerService.start(this);
            ServiceHealthStore.recordHeartbeat(this);
            ServiceHealthStore.recordNetworkAvailable(this);
            DeliveryWorker.drainAsync(this, macBaseUrl);
            listenerRunning = true;
            sectionError.setVisibility(View.GONE);
        } catch (RuntimeException error) {
            LOG.error("sms listener start failed", error);
            listenerRunning = false;
            showStatusToast(namedFormat(R.string.android_toast_listener_start_failed, "reason", error.getClass().getSimpleName()));
        }
        refreshConnectionStatus();
    }

    private void clearStoredPairing(String message) {
        pairingVerificationInFlight.set(false);
        lastPairingVerifiedAtMillis = 0L;
        new SecureTokenStore(this).clearCredential();
        PairingStore.clear(this);
        SmsListenerService.stop(this);
        ServiceHealthStore.clearHeartbeat(this);
        listenerRunning = false;
        connectionTestUiState = ConnectionTestUiState.idle(connectionTestCopy());
        pairingBaseUrl = "";
        pairingServiceName = "";
        sectionError.setVisibility(View.GONE);
        showStatusToast(message);
        refreshConnectionStatus();
    }

    private boolean hasReceiveSmsPermission() {
        return checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasReadSmsPermission() {
        return checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasNotificationPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
            || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isIgnoringBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return powerManager != null && powerManager.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || isIgnoringBatteryOptimizations()) {
            openBatteryOptimizationSettings();
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (ActivityNotFoundException missingRequestUi) {
            openBatteryOptimizationSettings();
        }
    }

    private void openBatteryOptimizationSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (ActivityNotFoundException missingSettings) {
            openAppPermissionSettings();
        }
    }

    private void openAppPermissionSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.fromParts("package", getPackageName(), null));
            startActivity(intent);
        } catch (ActivityNotFoundException missingSettings) {
        Toast.makeText(this, getString(R.string.android_toast_system_settings_unavailable), Toast.LENGTH_SHORT).show();
        }
    }

    private List<String> missingRuntimePermissions() {
        List<String> missingPermissions = new ArrayList<>();
        if (!hasReceiveSmsPermission()) missingPermissions.add(Manifest.permission.RECEIVE_SMS);
        if (!hasReadSmsPermission()) missingPermissions.add(Manifest.permission.READ_SMS);
        if (!hasNotificationPermission()) missingPermissions.add(Manifest.permission.POST_NOTIFICATIONS);
        return missingPermissions;
    }

    private String permissionStatus(List<String> missingPermissions) {
        List<String> labels = new ArrayList<>();
        if (missingPermissions.contains(Manifest.permission.RECEIVE_SMS)) labels.add(getString(R.string.android_permission_receive_sms_label));
        if (missingPermissions.contains(Manifest.permission.READ_SMS)) labels.add(getString(R.string.android_permission_read_network_sms_label));
        if (missingPermissions.contains(Manifest.permission.POST_NOTIFICATIONS)) labels.add(getString(R.string.android_permission_notification_label));
        if (labels.isEmpty()) return "";
        return namedFormat(R.string.android_permission_missing_permissions, "permissions", String.join(getString(R.string.android_permission_list_separator), labels));
    }

    private void startDiscovery() {
        discovery = new MacDiscoveryService(this);
        discovery.start(new MacDiscoveryService.Listener() {
            @Override
            public void onServicesChanged(List<NsdServiceInfo> services) {
                runOnUiThread(() -> renderServices(services));
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> status.setText(message));
            }
        });
    }

    private void restartDiscovery() {
        if (discovery != null) discovery.stop();
        startDiscovery();
    }

    private void renderServices(List<NsdServiceInfo> services) {
        discoveredServices = new ArrayList<>(services);
        recoverStoredEndpointFromDiscoveredServices(discoveredServices);
        if ((activeTab == TAB_DEVICES || activeTab == VIEW_DEVICE_SELECTION) && pairingBaseUrl.isEmpty()) {
            renderHome();
        }
    }

    private void recoverStoredEndpointFromDiscoveredServices(List<NsdServiceInfo> services) {
        if (services == null || services.isEmpty()) return;
        if (endpointRecoveryInFlight || pairingVerificationInFlight.get()) return;
        PairingCredential credential = new SecureTokenStore(this).loadCredential();
        if (credential == null || credential.requiresSecureRePairing()) return;
        PairingEndpoint current = PairingStore.loadEndpoint(this);
        if (current == null || current.isEmpty()) return;
        for (NsdServiceInfo service : services) {
            String baseUrl = MacEndpointUrls.baseUrl(service);
            if (baseUrl == null || baseUrl.equals(current.baseUrl)) continue;
            if (current.hasServiceIdentity() && !current.serviceName.equals(service.getServiceName())) continue;
            PairingEndpoint candidate = new PairingEndpoint(
                baseUrl,
                service.getServiceName(),
                current.serviceType,
                service.getPort(),
                current.secureProtocol,
                current.pairingSessionId,
                current.pairingExpiresAt
            );
            verifyDiscoveredEndpointAsync(candidate, credential);
            return;
        }
    }

    private void verifyDiscoveredEndpointAsync(PairingEndpoint candidate, PairingCredential credential) {
        if (candidate == null || candidate.isEmpty() || credential == null || credential.requiresSecureRePairing()) return;
        endpointRecoveryInFlight = true;
        new Thread(() -> {
            try {
                LOG.info("discovered endpoint verification start baseUrl={} serviceName={}", candidate.baseUrl, candidate.serviceName);
                SmsBridgeClient client = new SmsBridgeClient(candidate.baseUrl, new UrlConnectionTransport());
                reserveCredentialCounter(credential);
                PairingCredential verified = client.verifyPairing(credential);
                PairingStore.saveEndpoint(this, candidate);
                new SecureTokenStore(this).saveCredential(verified);
                LOG.info("discovered endpoint verification success baseUrl={}", candidate.baseUrl);
                runOnUiThread(() -> {
                    endpointRecoveryInFlight = false;
                    activateVerifiedPairing(candidate.baseUrl);
                });
            } catch (SmsBridgeClient.PairingRequiredException rejected) {
                LOG.warn("discovered endpoint verification rejected baseUrl={} reason={}", candidate.baseUrl, rejected.reason);
                runOnUiThread(() -> endpointRecoveryInFlight = false);
            } catch (Exception error) {
                LOG.warn("discovered endpoint verification failed baseUrl={}", candidate.baseUrl, error);
                runOnUiThread(() -> endpointRecoveryInFlight = false);
            }
        }, "SmsBridgeDiscoveredEndpointVerify").start();
    }

    private void showPairingForm(String baseUrl) {
        showPairingForm(PairingEndpoint.manual(baseUrl));
    }

    private void showPairingForm(PairingEndpoint endpoint) {
        String baseUrl = endpoint == null ? "" : endpoint.baseUrl;
        if (baseUrl == null || baseUrl.isEmpty()) {
            showStatusToast(getString(R.string.android_toast_mac_address_unavailable));
            return;
        }
        PairingStore.saveEndpoint(this, endpoint);
        pairingBaseUrl = baseUrl;
        pairingServiceName = endpoint.serviceName;
        activeTab = TAB_DEVICES;
        sectionError.setVisibility(View.GONE);
        refreshConnectionStatus();
    }

    private PairingEndpoint endpointFromService(NsdServiceInfo service, String baseUrl) {
        return PairingEndpoint.discovered(
            baseUrl,
            service == null ? "" : service.getServiceName(),
            txtAttribute(service, "pairingSessionId"),
            txtAttribute(service, "pairingExpiresAt")
        );
    }

    private String txtAttribute(NsdServiceInfo service, String key) {
        if (service == null || key == null) return "";
        java.util.Map<String, byte[]> attributes = service.getAttributes();
        if (attributes == null) return "";
        byte[] value = attributes.get(key);
        if (value == null || value.length == 0) return "";
        return new String(value, java.nio.charset.StandardCharsets.UTF_8).trim();
    }

    private void renderManualPairingScreen() {
        tabContent.setBackgroundColor(Color.WHITE);
        tabContent.setPadding(ConsoleTheme.dp(content, 20), 0, ConsoleTheme.dp(content, 20), 0);

        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setGravity(Gravity.CENTER_HORIZONTAL);
        screen.setPadding(0, ConsoleTheme.dp(content, 64), 0, ConsoleTheme.dp(content, 48));
        tabContent.addView(screen, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        FrameLayout iconShell = new FrameLayout(this);
        iconShell.setBackground(ConsoleTheme.rounded(content, Color.rgb(238, 237, 243), 16, ConsoleTheme.colorWithAlpha(ConsoleTheme.STROKE, 0), 0));
        iconShell.setElevation(ConsoleTheme.dp(content, 4));
        ImageView laptop = iconView(R.drawable.ic_bridge_laptop, ConsoleTheme.ACCENT_TEAL_DARK, 48);
        iconShell.addView(laptop, new FrameLayout.LayoutParams(
            ConsoleTheme.dp(content, 48),
            ConsoleTheme.dp(content, 48),
            Gravity.CENTER
        ));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(ConsoleTheme.dp(content, 96), ConsoleTheme.dp(content, 96));
        iconParams.gravity = Gravity.CENTER_HORIZONTAL;
        screen.addView(iconShell, iconParams);

        TextView title = ConsoleTheme.label(content, getString(R.string.android_manual_title), 24, Color.rgb(26, 27, 31), Typeface.NORMAL);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(0, ConsoleTheme.dp(content, 24), 0, 0);
        screen.addView(title, titleParams);

        TextView subtitle = ConsoleTheme.label(content, getString(R.string.android_manual_subtitle), 15, Color.rgb(65, 71, 85), Typeface.NORMAL);
        subtitle.setAlpha(0.7f);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleParams.setMargins(0, ConsoleTheme.dp(content, 18), 0, 0);
        screen.addView(subtitle, subtitleParams);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams formParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        formParams.setMargins(0, ConsoleTheme.dp(content, 50), 0, 0);
        screen.addView(form, formParams);

        EditText host = manualPairingInput("192.168.1.5", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        EditText port = manualPairingInput("55515", InputType.TYPE_CLASS_NUMBER);
        addManualPairingField(form, getString(R.string.android_manual_host), host, 0);
        addManualPairingField(form, getString(R.string.android_manual_port), port, 24);

        TextView error = ConsoleTheme.label(content, "", 13, ConsoleTheme.ACCENT_RED, Typeface.NORMAL);
        error.setGravity(Gravity.CENTER);
        error.setVisibility(View.GONE);
        LinearLayout.LayoutParams errorParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        errorParams.setMargins(0, ConsoleTheme.dp(content, 12), 0, 0);
        form.addView(error, errorParams);

        Button action = manualPairingActionButton(getString(R.string.android_manual_continue));
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ConsoleTheme.dp(content, 57));
        actionParams.setMargins(0, ConsoleTheme.dp(content, 36), 0, 0);
        action.setOnClickListener(v -> {
            String baseUrl;
            try {
                baseUrl = validateManualPairingEndpoint(host.getText().toString(), port.getText().toString());
            } catch (IllegalArgumentException invalid) {
                showManualPairingError(error, invalid.getMessage());
                if (host.getText().toString().trim().isEmpty()) {
                    host.requestFocus();
                } else {
                    port.requestFocus();
                }
                return;
            }
            connectManualPairingEndpoint(baseUrl, action, error);
        });
        form.addView(action, actionParams);

        host.requestFocus();
    }

    private void addManualPairingField(LinearLayout form, String labelText, EditText input, int topMarginDp) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams groupParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        groupParams.setMargins(0, ConsoleTheme.dp(content, topMarginDp), 0, 0);
        form.addView(group, groupParams);

        TextView label = ConsoleTheme.label(content, labelText, 17, Color.rgb(26, 27, 31), Typeface.NORMAL);
        group.addView(label);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ConsoleTheme.dp(content, 50));
        inputParams.setMargins(0, ConsoleTheme.dp(content, 8), 0, 0);
        group.addView(input, inputParams);
    }

    private EditText manualPairingInput(String hint, int inputType) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setTextColor(Color.rgb(26, 27, 31));
        input.setHintTextColor(ConsoleTheme.colorWithAlpha(Color.rgb(65, 71, 85), 102));
        input.setTextSize(17);
        input.setSingleLine(true);
        input.setInputType(inputType);
        input.setBackground(ConsoleTheme.rounded(content, Color.rgb(238, 237, 243), 12, ConsoleTheme.colorWithAlpha(ConsoleTheme.STROKE, 0), 0));
        input.setPadding(ConsoleTheme.dp(content, 16), 0, ConsoleTheme.dp(content, 16), 0);
        return input;
    }

    private Button manualPairingActionButton(String text) {
        Button button = ConsoleTheme.roundedButton(content, "", true);
        button.setTextSize(20);
        button.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        button.setTextColor(Color.WHITE);
        button.setBackground(ConsoleTheme.rounded(content, ConsoleTheme.ACCENT_TEAL, 12, ConsoleTheme.ACCENT_TEAL, 0));
        setManualPairingButtonState(button, text, true);
        Motion.applyPressScale(button);
        return button;
    }

    private void setManualPairingButtonState(Button button, String text, boolean enabled) {
        button.setText(getString(R.string.android_manual_continue).equals(text) ? text + "  →" : text);
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1.0f : 0.62f);
    }

    private void showManualPairingError(TextView error, String message) {
        error.setText(message == null || message.isEmpty() ? getString(R.string.android_manual_default_error) : message);
        error.setVisibility(View.VISIBLE);
        Motion.fadeInUp(error);
        Motion.shake(error);
    }

    private String validateManualPairingEndpoint(String hostValue, String portValue) {
        String host = normalizeManualPairingHost(hostValue);
        String portText = portValue == null ? "" : portValue.trim();
        if (host.isEmpty()) throw new IllegalArgumentException(getString(R.string.android_manual_host_required));
        if (portText.isEmpty()) throw new IllegalArgumentException(getString(R.string.android_manual_port_required));

        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException invalidPort) {
            throw new IllegalArgumentException(getString(R.string.android_manual_port_invalid));
        }
        if (port < 1 || port > 65535) throw new IllegalArgumentException(getString(R.string.android_manual_port_invalid));

        String baseHost = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        return "http://" + baseHost + ":" + port;
    }

    private String normalizeManualPairingHost(String hostValue) {
        String host = hostValue == null ? "" : hostValue.trim();
        if (host.isEmpty()) return "";
        try {
            if (host.regionMatches(true, 0, "http://", 0, 7) || host.regionMatches(true, 0, "https://", 0, 8)) {
                URI uri = URI.create(host);
                if (uri.getHost() != null && !uri.getHost().isEmpty()) {
                    host = uri.getHost();
                }
            }
        } catch (IllegalArgumentException ignored) {
            int scheme = host.indexOf("://");
            if (scheme >= 0) host = host.substring(scheme + 3);
        }
        int slash = host.indexOf('/');
        if (slash >= 0) host = host.substring(0, slash);
        int question = host.indexOf('?');
        if (question >= 0) host = host.substring(0, question);
        if (host.startsWith("[") && host.contains("]")) {
            return host.substring(1, host.indexOf(']')).trim();
        }
        int firstColon = host.indexOf(':');
        int lastColon = host.lastIndexOf(':');
        if (firstColon > 0 && firstColon == lastColon) {
            host = host.substring(0, firstColon);
        }
        return host.trim();
    }

    private void connectManualPairingEndpoint(String baseUrl, Button action, TextView error) {
        error.setVisibility(View.GONE);
        setManualPairingButtonState(action, getString(R.string.android_manual_connecting), false);
        long startedAt = SystemClock.uptimeMillis();
        new Thread(() -> {
            try {
                LOG.info("manual pairing health check start baseUrl={}", baseUrl);
                SmsBridgeClient client = new SmsBridgeClient(baseUrl, new UrlConnectionTransport());
                boolean reachable = client.testConnection();
                LOG.info("manual pairing health check result baseUrl={} reachable={}", baseUrl, reachable);
                completeConnectionTest(startedAt, () -> {
                    if (activeTab != VIEW_MANUAL_PAIRING) return;
                    if (reachable) {
                        showPairingForm(baseUrl);
                    } else {
                        setManualPairingButtonState(action, getString(R.string.android_manual_continue), true);
                        showManualPairingError(error, getString(R.string.android_manual_unreachable));
                    }
                });
            } catch (Exception failure) {
                LOG.error("manual pairing health check failed baseUrl={}", baseUrl, failure);
                completeConnectionTest(startedAt, () -> {
                    if (activeTab != VIEW_MANUAL_PAIRING) return;
                    setManualPairingButtonState(action, getString(R.string.android_manual_continue), true);
                    showManualPairingError(error, namedFormat(R.string.android_manual_failure_with_reason, "reason", errorMessage(failure)));
                });
            }
        }, "SmsBridgeManualPairing").start();
    }

    private void renderPairingCodeScreen(String baseUrl) {
        tabContent.setBackgroundColor(Color.WHITE);
        tabContent.setPadding(ConsoleTheme.dp(content, 20), 0, ConsoleTheme.dp(content, 20), 0);

        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setGravity(Gravity.CENTER_HORIZONTAL);
        screen.setPadding(0, ConsoleTheme.dp(content, 64), 0, ConsoleTheme.dp(content, 48));
        tabContent.addView(screen, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        FrameLayout iconShell = new FrameLayout(this);
        iconShell.setBackground(ConsoleTheme.rounded(content, Color.rgb(238, 237, 243), 16, ConsoleTheme.colorWithAlpha(ConsoleTheme.STROKE, 0), 0));
        iconShell.setElevation(ConsoleTheme.dp(content, 4));
        ImageView laptop = iconView(R.drawable.ic_bridge_laptop, ConsoleTheme.ACCENT_TEAL_DARK, 48);
        iconShell.addView(laptop, new FrameLayout.LayoutParams(
            ConsoleTheme.dp(content, 48),
            ConsoleTheme.dp(content, 48),
            Gravity.CENTER
        ));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(ConsoleTheme.dp(content, 96), ConsoleTheme.dp(content, 96));
        iconParams.gravity = Gravity.CENTER_HORIZONTAL;
        screen.addView(iconShell, iconParams);

        TextView pairingStatus = pairingStatusChip(pairingStatusText(baseUrl));
        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ConsoleTheme.dp(content, 30));
        chipParams.gravity = Gravity.CENTER_HORIZONTAL;
        chipParams.setMargins(0, ConsoleTheme.dp(content, 52), 0, 0);
        screen.addView(pairingStatus, chipParams);

        TextView title = ConsoleTheme.label(content, getString(R.string.android_pairing_title), 24, Color.rgb(26, 27, 31), Typeface.NORMAL);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(0, ConsoleTheme.dp(content, 18), 0, 0);
        screen.addView(title, titleParams);

        TextView subtitle = ConsoleTheme.label(content, getString(R.string.android_pairing_subtitle), 15, Color.rgb(65, 71, 85), Typeface.NORMAL);
        subtitle.setAlpha(0.7f);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleParams.setMargins(0, ConsoleTheme.dp(content, 12), 0, 0);
        screen.addView(subtitle, subtitleParams);

        TextView error = ConsoleTheme.label(content, "", 13, ConsoleTheme.ACCENT_RED, Typeface.NORMAL);
        error.setGravity(Gravity.CENTER);
        error.setVisibility(View.GONE);

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ConsoleTheme.dp(content, 56));
        rowParams.setMargins(0, ConsoleTheme.dp(content, 48), 0, 0);
        screen.addView(inputRow, rowParams);

        TextView[] codeSlots = new TextView[6];
        EditText[] codeInputs = new EditText[6];
        Button confirm = pairingConfirmButton(false);
        for (int i = 0; i < codeInputs.length; i++) {
            EditText input = pairingCodeInput();
            final int index = i;
            input.setContentDescription(namedFormat(R.string.android_pairing_code_digit_description, "index", String.valueOf(index + 1)));
            input.addTextChangedListener(new android.text.TextWatcher() {
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (s.length() == 1 && index < codeInputs.length - 1) {
                        codeInputs[index + 1].requestFocus();
                    }
                    error.setVisibility(View.GONE);
                    setPairingButtonEnabled(confirm, collectPairingCode(codeInputs).length() == 6);
                }
                public void afterTextChanged(android.text.Editable s) {}
            });
            input.setOnKeyListener((v, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_DEL && event.getAction() == KeyEvent.ACTION_DOWN) {
                    return handlePairingCodeDelete(codeInputs, index, confirm, error);
                }
                return false;
            });
            codeInputs[i] = input;
            codeSlots[i] = input;
            LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, ConsoleTheme.dp(content, 56), 1);
            if (i > 0) inputParams.setMargins(ConsoleTheme.dp(content, 12), 0, 0, 0);
            inputRow.addView(input, inputParams);
        }

        LinearLayout.LayoutParams errorParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        errorParams.setMargins(0, ConsoleTheme.dp(content, 10), 0, 0);
        screen.addView(error, errorParams);

        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ConsoleTheme.dp(content, 56));
        buttonParams.setMargins(0, ConsoleTheme.dp(content, 48), 0, 0);
        confirm.setOnClickListener(v -> {
            String pairingCode = collectPairingCode(codeInputs);
            if (pairingCode.length() != 6) {
                error.setText(getString(R.string.android_pairing_incomplete));
                error.setVisibility(View.VISIBLE);
                Motion.shake(inputRow);
                return;
            }
            setPairingButtonEnabled(confirm, false);
            confirm.setText(getString(R.string.android_pairing_in_progress));
            String serviceName = pairingServiceName;
            new Thread(() -> pairInBackground(baseUrl, pairingCode, serviceName, codeInputs, inputRow, error, confirm)).start();
        });
        screen.addView(confirm, buttonParams);

        if (codeInputs.length > 0) {
            showPairingKeyboard(codeInputs[0]);
        }
    }

    private void showPairingKeyboard(EditText input) {
        if (input == null) return;
        input.post(() -> {
            input.requestFocus();
            InputMethodManager inputMethod = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (inputMethod != null) inputMethod.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private boolean handlePairingCodeDelete(EditText[] codeInputs, int index, Button confirm, TextView error) {
        if (index < 0 || index >= codeInputs.length) return false;
        EditText current = codeInputs[index];
        if (current == null) return false;
        if (current.getText().length() > 0) {
            current.getText().clear();
            updatePairingCodeAfterDelete(codeInputs, confirm, error);
            return true;
        }
        if (index == 0 || codeInputs[index - 1] == null) return false;
        EditText previous = codeInputs[index - 1];
        previous.getText().clear();
        previous.requestFocus();
        previous.setSelection(0);
        updatePairingCodeAfterDelete(codeInputs, confirm, error);
        return true;
    }

    private void updatePairingCodeAfterDelete(EditText[] codeInputs, Button confirm, TextView error) {
        error.setVisibility(View.GONE);
        setPairingButtonEnabled(confirm, collectPairingCode(codeInputs).length() == 6);
    }

    private EditText pairingCodeInput() {
        EditText input = new EditText(this);
        input.setTextColor(Color.rgb(26, 27, 31));
        input.setTextSize(24);
        input.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        input.setGravity(Gravity.CENTER);
        input.setSingleLine(true);
        input.setSelectAllOnFocus(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setFilters(new InputFilter[] { new InputFilter.LengthFilter(1) });
        input.setBackground(ConsoleTheme.rounded(content, Color.rgb(244, 243, 248), 12, Color.rgb(193, 198, 215), 1));
        input.setPadding(0, 0, 0, 0);
        return input;
    }

    private TextView pairingStatusChip(String text) {
        TextView chip = ConsoleTheme.label(content, text, 13, Color.rgb(65, 71, 85), Typeface.NORMAL);
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        chip.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.presence_online, 0, 0, 0);
        chip.setCompoundDrawableTintList(ColorStateList.valueOf(ConsoleTheme.ACCENT_TEAL_DARK));
        chip.setCompoundDrawablePadding(ConsoleTheme.dp(content, 8));
        chip.setPadding(ConsoleTheme.dp(content, 12), 0, ConsoleTheme.dp(content, 12), 0);
        chip.setBackground(ConsoleTheme.rounded(content, Color.rgb(244, 243, 248), 99, ConsoleTheme.colorWithAlpha(Color.rgb(193, 198, 215), 76), 1));
        return chip;
    }

    private String pairingStatusText(String baseUrl) {
        return namedFormat(R.string.android_pairing_with_device, "deviceName", shortMacDisplayName(baseUrl));
    }

    private Button pairingConfirmButton(boolean enabled) {
        Button button = ConsoleTheme.roundedButton(content, getString(R.string.android_pairing_confirm), true);
        button.setTextSize(20);
        button.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        setPairingButtonEnabled(button, enabled);
        return button;
    }

    private void setPairingButtonEnabled(Button button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1.0f : 0.5f);
    }

    private String collectPairingCode(EditText[] codeInputs) {
        StringBuilder builder = new StringBuilder();
        for (EditText input : codeInputs) {
            builder.append(input.getText().toString().trim());
        }
        return builder.toString();
    }

    private void focusFirstPairingInput(EditText[] codeInputs) {
        for (EditText input : codeInputs) {
            if (input.getText().length() == 0) {
                input.requestFocus();
                return;
            }
        }
        if (codeInputs.length > 0) {
            codeInputs[codeInputs.length - 1].requestFocus();
        }
    }

    private void pairInBackground(String baseUrl, String pairingCode, String serviceName, EditText[] codeInputs, View codeRow, TextView errorLabel, Button pair) {
        try {
            LOG.info("pairing start baseUrl={}", baseUrl);
            SmsBridgeClient client = new SmsBridgeClient(baseUrl, new UrlConnectionTransport());
            PairingEndpoint endpoint = PairingStore.loadEndpoint(this);
            try {
                SmsBridgeClient.PairingSession session = client.fetchPairingSession();
                endpoint = endpoint.withSecureSession(
                    session.serviceName,
                    session.secureProtocol,
                    session.pairingSessionId,
                    session.pairingExpiresAt
                );
                PairingStore.saveEndpoint(this, endpoint.withBaseUrl(baseUrl));
                LOG.info("pairing session refreshed baseUrl={} serviceName={}", baseUrl, endpoint.serviceName);
            } catch (IOException sessionError) {
                LOG.warn("pairing session refresh failed baseUrl={}", baseUrl, sessionError);
                if (endpoint.pairingSessionId.isEmpty()) throw sessionError;
            }
            if (endpoint.pairingSessionId.isEmpty()) {
                throw new IllegalStateException(getString(R.string.android_pairing_session_unavailable));
            }
            String desktopName = endpoint.serviceName.isEmpty() ? (serviceName == null ? "" : serviceName) : endpoint.serviceName;
            PairingCredential credential = client.pairSecure(
                pairingCode,
                endpoint.pairingSessionId,
                Build.MODEL,
                ClientIdentityStore.clientInstanceId(this),
                desktopName,
                endpoint.pairingExpiresAt
            );
            new SecureTokenStore(this).saveCredential(credential);
            PairingStore.saveEndpoint(this, endpoint.withBaseUrl(baseUrl));
            LOG.info("pairing success baseUrl={} deviceId={}", baseUrl, credential.deviceId);
            runOnUiThread(() -> {
                listenerRunning = true;
                activeTab = TAB_DEVICES;
                pairingBaseUrl = "";
                pairingServiceName = "";
                activateVerifiedPairing(baseUrl);
            });
        } catch (Exception error) {
            PairingFeedback feedback = PairingFeedback.from(error, pairingFeedbackCopy());
            LOG.error("pairing failed baseUrl={}", baseUrl, error);
            runOnUiThread(() -> {
                pair.setText(getString(R.string.android_pairing_confirm));
                setPairingButtonEnabled(pair, collectPairingCode(codeInputs).length() == 6);
                if (!feedback.inlineError.isEmpty()) {
                    errorLabel.setText(feedback.inlineError);
                    errorLabel.setVisibility(View.VISIBLE);
                    Motion.shake(codeRow);
                    focusFirstPairingInput(codeInputs);
                }
                if (!feedback.sectionError.isEmpty()) {
                    errorLabel.setText(feedback.sectionError);
                    errorLabel.setVisibility(View.VISIBLE);
                    Motion.shake(codeRow);
                }
            });
        }
    }

    private void testConnection() {
        if (!connectionTestUiState.buttonEnabled) return;
        String baseUrl = PairingStore.loadMacBaseUrl(this);
        PairingCredential credential = new SecureTokenStore(this).loadCredential();
        if (baseUrl.isEmpty()) {
            connectionTestUiState = ConnectionTestUiState.failure(getString(R.string.android_toast_no_saved_mac), connectionTestCopy());
            showStatusToast(getString(R.string.android_toast_no_saved_mac));
            refreshConnectionStatus();
            LOG.info("connection test skipped: no saved Mac");
            return;
        }
        if (credential == null || credential.requiresSecureRePairing()) {
            clearStoredPairing(getString(R.string.android_toast_pair_again_required));
            return;
        }
        connectionTestUiState = ConnectionTestUiState.running(connectionTestCopy());
        refreshConnectionStatus();
        long startedAt = SystemClock.uptimeMillis();
        new Thread(() -> {
            try {
                LOG.info("connection test start baseUrl={}", baseUrl);
                SmsBridgeClient client = new SmsBridgeClient(baseUrl, new UrlConnectionTransport());
                reserveCredentialCounter(credential);
                PairingCredential verified = client.verifyPairing(credential);
                new SecureTokenStore(this).saveCredential(verified);
                boolean reachable = client.testConnection();
                LOG.info("connection test result baseUrl={} reachable={}", baseUrl, reachable);
                completeConnectionTest(startedAt, () -> {
                    if (reachable) {
                        connectionTestUiState = ConnectionTestUiState.success(baseUrl, connectionTestCopy());
                        showStatusToast(getString(R.string.android_toast_connection_ok));
                        activateVerifiedPairing(baseUrl);
                    } else {
                        listenerRunning = false;
                        ServiceHealthStore.recordNetworkLost(this);
                        connectionTestUiState = ConnectionTestUiState.failure(namedFormat(R.string.android_toast_mac_unreachable, "baseUrl", baseUrl), connectionTestCopy());
                        showStatusToast(connectionTestUiState.feedbackText);
                        refreshConnectionStatus();
                    }
                });
            } catch (SmsBridgeClient.PairingRequiredException revoked) {
                LOG.warn("connection test pairing revoked reason={}", revoked.reason);
                completeConnectionTest(startedAt, () -> clearStoredPairing(getString(R.string.android_toast_revoked)));
            } catch (Exception error) {
                LOG.error("connection test failed baseUrl={}", baseUrl, error);
                completeConnectionTest(startedAt, () -> {
                    listenerRunning = false;
                    ServiceHealthStore.recordNetworkLost(this);
                    connectionTestUiState = ConnectionTestUiState.failure(namedFormat(R.string.android_toast_connection_failed, "reason", errorMessage(error)), connectionTestCopy());
                    showStatusToast(connectionTestUiState.feedbackText);
                    refreshConnectionStatus();
                });
            }
        }).start();
    }

    private void reserveCredentialCounter(PairingCredential credential) {
        if (credential == null || credential.requiresSecureRePairing()) return;
        new SecureTokenStore(this).saveCredential(credential.withNextCounter(credential.nextCounter + 1L));
    }

    private void completeConnectionTest(long startedAt, Runnable completion) {
        long elapsed = SystemClock.uptimeMillis() - startedAt;
        long delay = Math.max(0L, CONNECTION_TEST_MIN_VISIBLE_MS - elapsed);
        runOnUiThread(() -> {
            if (delay > 0L && content != null) {
                content.postDelayed(completion, delay);
            } else {
                completion.run();
            }
        });
    }

    private String errorMessage(Exception error) {
        if (error == null) return getString(R.string.android_error_unknown);
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private void showStatusToast(String message) {
        if (message == null || message.isEmpty()) return;
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void applySafeAreaPadding(ScrollView scroll, LinearLayout root, LinearLayout bottomHost) {
        int basePadding = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(basePadding, basePadding, basePadding, basePadding);
        bottomHost.setPadding(basePadding, 0, basePadding, basePadding);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            int topPadding = statusBarInset(insets);
            systemBottomInset = navigationBarInset(insets);
            view.setPadding(0, topPadding, 0, 0);
            if (content == null) {
                bottomHost.setPadding(basePadding, 0, basePadding, Math.max(basePadding, systemBottomInset));
            } else {
                bottomHost.setPadding(
                    0,
                    0,
                    0,
                    bottomHostBottomPadding(currentWhiteFlowVisible)
                );
            }
            return insets;
        });
    }

    private void configureSystemBars() {
        getWindow().setStatusBarColor(ConsoleTheme.BACKGROUND);
        getWindow().setNavigationBarColor(ConsoleTheme.BACKGROUND);
        int flags = getWindow().getDecorView().getSystemUiVisibility();
        flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private static int statusBarInset(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return insets.getInsets(WindowInsets.Type.statusBars()).top;
        }
        return insets.getSystemWindowInsetTop();
    }

    private static int navigationBarInset(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
        }
        return insets.getSystemWindowInsetBottom();
    }

    @Override
    protected void onDestroy() {
        unregisterConnectionStatusRefreshReceiver();
        if (discovery != null) discovery.stop();
        super.onDestroy();
    }
}
