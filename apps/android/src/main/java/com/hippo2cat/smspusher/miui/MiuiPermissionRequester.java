package com.hippo2cat.smspusher.miui;

import android.app.Activity;
import android.app.AppOpsManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;

import java.lang.reflect.Method;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MiuiPermissionRequester {
    private static final Logger LOG = LoggerFactory.getLogger("SmsBridge");
    private static final String PREFS = "sms_bridge_miui_permissions";
    private static final String KEY_NOTIFICATION_SMS_PROMPTED = "notificationSmsPrompted";

    private MiuiPermissionRequester() {}

    public static boolean isMiuiDevice() {
        return MiuiPermissionGuide.isMiui(
            Build.MANUFACTURER,
            systemProperty("ro.miui.ui.version.name"),
            systemProperty("ro.mi.os.version.name")
        );
    }

    public static void promptIfNeeded(Activity activity) {
        boolean miui = isMiuiDevice();
        if (!miui) return;
        boolean allowed = isNotificationSmsAllowed(activity);
        SharedPreferences preferences = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean prompted = preferences.getBoolean(KEY_NOTIFICATION_SMS_PROMPTED, false);
        if (!MiuiPermissionGuide.shouldPrompt(miui, allowed, prompted)) return;

        if (openPermissionSettings(activity)) {
            preferences.edit().putBoolean(KEY_NOTIFICATION_SMS_PROMPTED, true).apply();
        }
    }

    public static boolean openPermissionSettings(Activity activity) {
        return openMiuiPermissionEditor(activity) || openAppDetails(activity);
    }

    public static boolean openBackgroundSettings(Activity activity) {
        return openMiuiPowerKeeper(activity)
            || openMiuiHiddenApps(activity)
            || openAppDetails(activity);
    }

    public static boolean isNotificationSmsAllowed(Context context) {
        try {
            AppOpsManager manager = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (manager == null) return false;
            Method method = AppOpsManager.class.getMethod(
                "checkOpNoThrow",
                int.class,
                int.class,
                String.class
            );
            int result = (int) method.invoke(
                manager,
                MiuiPermissionGuide.NOTIFICATION_SMS_APP_OP,
                Process.myUid(),
                context.getPackageName()
            );
            return result == AppOpsManager.MODE_ALLOWED;
        } catch (Exception error) {
            LOG.warn("miui notification sms permission check failed", error);
            return false;
        }
    }

    static Intent miuiPermissionEditorIntent(String packageName) {
        Intent intent = new Intent("miui.intent.action.APP_PERM_EDITOR");
        intent.setClassName(
            "com.miui.securitycenter",
            "com.miui.permcenter.permissions.PermissionsEditorActivity"
        );
        intent.putExtra("extra_pkgname", packageName);
        intent.putExtra("extra_package_name", packageName);
        intent.putExtra("extra_package_uid", Process.myUid());
        return intent;
    }

    private static boolean openMiuiPermissionEditor(Activity activity) {
        try {
            activity.startActivity(miuiPermissionEditorIntent(activity.getPackageName()));
            LOG.info("opened MIUI permission editor");
            return true;
        } catch (ActivityNotFoundException missingNewEditor) {
            try {
                Intent legacy = new Intent("miui.intent.action.APP_PERM_EDITOR");
                legacy.setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.AppPermissionsEditorActivity"
                );
                legacy.putExtra("extra_pkgname", activity.getPackageName());
                legacy.putExtra("extra_package_name", activity.getPackageName());
                legacy.putExtra("extra_package_uid", Process.myUid());
                activity.startActivity(legacy);
                LOG.info("opened legacy MIUI permission editor");
                return true;
            } catch (ActivityNotFoundException missingLegacyEditor) {
                LOG.warn("MIUI permission editor unavailable", missingLegacyEditor);
                return false;
            }
        }
    }

    private static boolean openMiuiPowerKeeper(Activity activity) {
        try {
            Intent intent = new Intent("miui.intent.action.POWER_HIDE_MODE_APP_LIST");
            activity.startActivity(intent);
            LOG.info("opened MIUI power keeper settings");
            return true;
        } catch (ActivityNotFoundException missingPowerKeeper) {
            LOG.warn("MIUI power keeper settings unavailable", missingPowerKeeper);
            return false;
        }
    }

    private static boolean openMiuiHiddenApps(Activity activity) {
        try {
            Intent intent = new Intent();
            intent.setClassName(
                "com.miui.powerkeeper",
                "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
            );
            intent.putExtra("package_name", activity.getPackageName());
            intent.putExtra("package_label", "SmsPusher");
            activity.startActivity(intent);
            LOG.info("opened MIUI hidden apps config");
            return true;
        } catch (ActivityNotFoundException missingHiddenApps) {
            LOG.warn("MIUI hidden apps config unavailable", missingHiddenApps);
            return false;
        }
    }

    private static boolean openAppDetails(Activity activity) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.fromParts("package", activity.getPackageName(), null));
            activity.startActivity(intent);
            LOG.info("opened app details settings for MIUI permission fallback");
            return true;
        } catch (ActivityNotFoundException missingSettings) {
            LOG.warn("app details settings unavailable", missingSettings);
            return false;
        }
    }

    private static String systemProperty(String key) {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            return (String) properties.getMethod("get", String.class).invoke(null, key);
        } catch (Exception ignored) {
            return "";
        }
    }
}
