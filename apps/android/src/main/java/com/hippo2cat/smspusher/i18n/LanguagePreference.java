package com.hippo2cat.smspusher.i18n;

public enum LanguagePreference {
    AUTO("auto"),
    ZH_CN("zh-CN"),
    EN_US("en-US");

    public final String storageValue;

    LanguagePreference(String storageValue) {
        this.storageValue = storageValue;
    }

    public static LanguagePreference fromStorageValue(String value) {
        for (LanguagePreference preference : values()) {
            if (preference.storageValue.equals(value)) return preference;
        }
        return AUTO;
    }
}
