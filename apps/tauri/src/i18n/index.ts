import i18n from "i18next";
import { initReactI18next } from "react-i18next";
import enUS from "./generated/en-US.json";
import zhCN from "./generated/zh-CN.json";
import type { LanguagePreference } from "../types";

export type ResolvedLocale = "en-US" | "zh-CN";

export const supportedLocales: ResolvedLocale[] = ["en-US", "zh-CN"];

export function resolveLocale(
  preference: LanguagePreference | null | undefined,
  systemLocales: readonly string[] = navigator.languages,
): ResolvedLocale {
  if (preference === "zh-CN") return "zh-CN";
  if (preference === "en-US") return "en-US";

  for (const locale of systemLocales) {
    const normalized = locale.replace("_", "-");
    if (
      normalized === "zh" ||
      normalized.startsWith("zh-CN") ||
      normalized.startsWith("zh-Hans")
    ) {
      return "zh-CN";
    }
  }

  return "en-US";
}

export function changeAppLanguage(
  preference: LanguagePreference | null | undefined,
) {
  return i18n.changeLanguage(resolveLocale(preference));
}

export const i18nReady = i18n.use(initReactI18next).init({
  resources: {
    "en-US": { translation: enUS },
    "zh-CN": { translation: zhCN },
  },
  lng: resolveLocale("auto"),
  fallbackLng: "en-US",
  keySeparator: false,
  interpolation: { escapeValue: false },
});

export default i18n;
