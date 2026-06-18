package com.hippo2cat.smspusher;

import android.app.Application;

import com.hippo2cat.smspusher.logging.AppLogging;

public final class SmsPusherApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        AppLogging.configure(this);
    }
}
