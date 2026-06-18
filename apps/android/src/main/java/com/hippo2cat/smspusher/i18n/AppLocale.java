package com.hippo2cat.smspusher.i18n;

import android.content.Context;
import android.content.res.Configuration;
import android.os.LocaleList;

import java.util.Locale;

public final class AppLocale {
    private static final Locale EN_US = Locale.forLanguageTag("en-US");
    private static final Locale ZH_CN = Locale.forLanguageTag("zh-CN");

    private AppLocale() {}

    public static Context wrap(Context context) {
        Locale locale = resolve(LanguagePreferenceStore.get(context), Locale.getDefault());
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.setLocale(locale);
        configuration.setLocales(new LocaleList(locale));
        return context.createConfigurationContext(configuration);
    }

    public static Locale resolve(LanguagePreference preference, Locale systemLocale) {
        if (preference == LanguagePreference.ZH_CN) return ZH_CN;
        if (preference == LanguagePreference.EN_US) return EN_US;
        String tag = systemLocale == null ? "" : systemLocale.toLanguageTag();
        String language = systemLocale == null ? "" : systemLocale.getLanguage();
        if ("zh".equals(language) || "zh-CN".equals(tag) || tag.startsWith("zh-Hans")) return ZH_CN;
        return EN_US;
    }
}
