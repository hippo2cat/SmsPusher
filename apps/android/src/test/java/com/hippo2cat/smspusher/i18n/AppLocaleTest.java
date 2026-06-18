package com.hippo2cat.smspusher.i18n;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Locale;

public final class AppLocaleTest {
    @Test
    public void languagePreferenceRoundTripsStoredValues() {
        assertEquals(LanguagePreference.AUTO, LanguagePreference.fromStorageValue(""));
        assertEquals(LanguagePreference.AUTO, LanguagePreference.fromStorageValue("auto"));
        assertEquals(LanguagePreference.ZH_CN, LanguagePreference.fromStorageValue("zh-CN"));
        assertEquals(LanguagePreference.EN_US, LanguagePreference.fromStorageValue("en-US"));
        assertEquals(LanguagePreference.AUTO, LanguagePreference.fromStorageValue("fr-FR"));
    }

    @Test
    public void localeResolutionFollowsPreferenceThenSystemThenEnglish() {
        assertEquals("zh-CN", AppLocale.resolve(LanguagePreference.ZH_CN, Locale.US).toLanguageTag());
        assertEquals("en-US", AppLocale.resolve(LanguagePreference.EN_US, Locale.SIMPLIFIED_CHINESE).toLanguageTag());
        assertEquals("zh-CN", AppLocale.resolve(LanguagePreference.AUTO, Locale.SIMPLIFIED_CHINESE).toLanguageTag());
        assertEquals("zh-CN", AppLocale.resolve(LanguagePreference.AUTO, Locale.forLanguageTag("zh-Hans-CN")).toLanguageTag());
        assertEquals("zh-CN", AppLocale.resolve(LanguagePreference.AUTO, Locale.forLanguageTag("zh")).toLanguageTag());
        assertEquals("en-US", AppLocale.resolve(LanguagePreference.AUTO, Locale.FRANCE).toLanguageTag());
    }
}
