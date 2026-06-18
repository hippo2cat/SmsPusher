package com.hippo2cat.smspusher.logging;

import android.content.Context;
import android.util.Log;

import org.slf4j.LoggerFactory;

import java.io.File;

public final class AppLogging {
    public static final String LOG_DIR_PROPERTY = "SM_PUSHER_LOG_DIR";
    private static volatile boolean configured;

    private AppLogging() {}

    public static void configure(Context context) {
        if (context == null || configured) return;
        File logDir = logDir(context);
        if (!logDir.exists() && !logDir.mkdirs()) {
            Log.w("SmsBridge", "Unable to create log directory: " + logDir.getAbsolutePath());
        }
        System.setProperty("SM_PUSHER_LOG_DIR", logDir.getAbsolutePath());
        configured = true;
        LoggerFactory.getLogger("SmsBridge").info("logging configured logDir={}", logDir.getAbsolutePath());
    }

    public static File logDir(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), "logs");
    }
}
