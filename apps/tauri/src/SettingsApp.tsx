import { useCallback, useEffect, useState } from "react";
import { getVersion } from "@tauri-apps/api/app";
import {
  Bell,
  Check,
  Globe2,
  Info,
  Languages,
  Network,
  Power,
  RefreshCw,
  Router,
  Settings,
} from "lucide-react";
import appIcon from "../src-tauri/icons/icon.png";
import { useTranslation } from "react-i18next";
import { changeAppLanguage, resolveLocale } from "./i18n";
import {
  checkForUpdates,
  getAutostartEnabled,
  getSettings,
  listenToUpdateCheckProgress,
  listNetworkInterfaces,
  setAutostartEnabled,
  updateSettings,
} from "./tauri";
import type {
  AppSettingsSnapshot,
  LanguagePreference,
  NetworkInterfaceSnapshot,
  UpdateCheckOutcome,
  UpdateCheckProgressEvent,
} from "./types";

type SettingsSection = "general" | "lan" | "update" | "about";
type UpdateProxyMode = "none" | "system" | "manual";

const sections: Array<{
  id: SettingsSection;
  icon: typeof Settings;
  labelKey: string;
}> = [
  { id: "general", icon: Settings, labelKey: "settings.nav.general" },
  { id: "lan", icon: Network, labelKey: "settings.nav.lan" },
  { id: "update", icon: RefreshCw, labelKey: "settings.nav.update" },
  { id: "about", icon: Info, labelKey: "settings.nav.about" },
];

const updateProxyModes: Array<{
  value: UpdateProxyMode;
  labelKey: string;
}> = [
  { value: "none", labelKey: "settings.update.proxyNone" },
  { value: "system", labelKey: "settings.update.proxySystem" },
  { value: "manual", labelKey: "settings.update.proxyManual" },
];

function ToggleRow({
  icon: Icon,
  label,
  detail,
  checked,
  disabled,
  onChange,
}: {
  icon: typeof Settings;
  label: string;
  detail?: string;
  checked: boolean;
  disabled?: boolean;
  onChange: (checked: boolean) => void;
}) {
  return (
    <label className="settings-toggle-row">
      <span className="settings-row-icon">
        <Icon size={16} strokeWidth={2.1} />
      </span>
      <span className="settings-row-copy">
        <strong>{label}</strong>
        {detail ? <small>{detail}</small> : null}
      </span>
      <input
        type="checkbox"
        checked={checked}
        disabled={disabled}
        onChange={(event) => onChange(event.target.checked)}
      />
    </label>
  );
}

export default function SettingsApp() {
  const { t, i18n } = useTranslation();
  const [active, setActive] = useState<SettingsSection>("general");
  const [settings, setSettings] = useState<AppSettingsSnapshot | null>(null);
  const [interfaces, setInterfaces] = useState<NetworkInterfaceSnapshot[]>([]);
  const [autostartEnabled, setAutostartEnabledState] = useState(false);
  const [version, setVersion] = useState("");
  const [proxyUrl, setProxyUrl] = useState("");
  const [portValue, setPortValue] = useState("");
  const [updateProgress, setUpdateProgress] = useState<UpdateCheckProgressEvent | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    const [
      nextSettings,
      nextInterfaces,
      nextAutostartEnabled,
      nextVersion,
    ] = await Promise.all([
      getSettings(),
      listNetworkInterfaces(),
      getAutostartEnabled(),
      getVersion(),
    ]);
    setSettings(nextSettings);
    setInterfaces(nextInterfaces);
    setAutostartEnabledState(nextAutostartEnabled);
    setVersion(nextVersion);
    setProxyUrl(nextSettings.updateProxyUrl);
    setPortValue(String(nextSettings.preferredPort));
    setError("");
  }, []);

  useEffect(() => {
    load().catch((loadError) => setError(String(loadError)));
  }, [load]);

  useEffect(() => {
    if (!notice) return;
    const timeout = window.setTimeout(() => setNotice(""), 1800);
    return () => window.clearTimeout(timeout);
  }, [notice]);

  useEffect(() => {
    if (!settings?.languagePreference) return;
    if (i18n.language === resolveLocale(settings.languagePreference)) return;
    changeAppLanguage(settings.languagePreference).catch((languageError) =>
      setError(String(languageError)),
    );
  }, [settings?.languagePreference, i18n.language]);

  useEffect(() => {
    let dispose: (() => void) | null = null;
    let cancelled = false;

    listenToUpdateCheckProgress((event) => {
      if (cancelled) return;
      applyUpdateProgress(event);
    })
      .then((unlisten) => {
        if (cancelled) {
          unlisten();
          return;
        }
        dispose = unlisten;
      })
      .catch((listenError) => setError(String(listenError)));

    return () => {
      cancelled = true;
      dispose?.();
    };
  }, [t]);

  function selectSection(section: SettingsSection) {
    setActive(section);
    setNotice("");
    setError("");
  }

  async function saveSettings(update: Parameters<typeof updateSettings>[0]) {
    const nextSettings = await updateSettings(update);
    setSettings(nextSettings);
    setProxyUrl(nextSettings.updateProxyUrl);
    setPortValue(String(nextSettings.preferredPort));
    return nextSettings;
  }

  async function chooseLanguage(value: LanguagePreference) {
    setBusy("language");
    try {
      const nextSettings = await saveSettings({ languagePreference: value });
      await changeAppLanguage(nextSettings.languagePreference);
      setNotice(t("settings.saved"));
      setError("");
    } catch (languageError) {
      setError(String(languageError));
    } finally {
      setBusy(null);
    }
  }

  async function toggleAutostart(value: boolean) {
    setBusy("autostart");
    try {
      const enabled = await setAutostartEnabled(value);
      setAutostartEnabledState(enabled);
      setNotice(t("settings.saved"));
      setError("");
    } catch (autostartError) {
      setError(String(autostartError));
    } finally {
      setBusy(null);
    }
  }

  async function updateSetting(update: Parameters<typeof updateSettings>[0], key: string) {
    setBusy(key);
    try {
      await saveSettings(update);
      setNotice(t("settings.saved"));
      setError("");
    } catch (settingsError) {
      setError(String(settingsError));
    } finally {
      setBusy(null);
    }
  }

  async function savePort() {
    const parsed = Number(portValue);
    if (!Number.isInteger(parsed) || parsed < 1 || parsed > 65535) {
      setError(t("settings.lan.portInvalid"));
      return;
    }
    await updateSetting({ preferredPort: parsed }, "port");
  }

  async function saveProxy() {
    await updateSetting({ updateProxyUrl: proxyUrl.trim() }, "proxyUrl");
  }

  async function chooseProxyMode(value: UpdateProxyMode) {
    await updateSetting({ updateProxyMode: value }, "proxyMode");
  }

  function outcomeMessage(outcome: UpdateCheckOutcome) {
    if (outcome.status === "upToDate") {
      return t("settings.update.noUpdate");
    }
    if (outcome.status === "installerOpened") {
      return t("settings.update.installerOpened", { version: outcome.version });
    }
    return t("settings.update.unsupported");
  }

  function applyUpdateProgress(event: UpdateCheckProgressEvent) {
    setUpdateProgress(event);
    if (event.status === "failed") {
      setNotice("");
      setError(t("settings.update.checkFailed", { message: event.message }));
      return;
    }
    if (event.status === "checking" || event.status === "downloading") {
      setError("");
      return;
    }
    setNotice(outcomeMessage(event));
    setError("");
  }

  async function runUpdateCheck() {
    setBusy("checkUpdates");
    setUpdateProgress({ status: "checking" });
    setNotice("");
    setError("");
    try {
      if (settings?.updateProxyMode === "manual" && proxyUrl.trim() !== settings.updateProxyUrl) {
        await saveSettings({ updateProxyUrl: proxyUrl.trim() });
      }
      const outcome = await checkForUpdates();
      applyUpdateProgress(outcome);
      setError("");
    } catch (updateError) {
      const message = String(updateError);
      setUpdateProgress({ status: "failed", message });
      setError(t("settings.update.checkFailed", { message }));
    } finally {
      setBusy(null);
    }
  }

  const selectedInterface = settings?.networkInterfaceId ?? "auto";
  const selectedLanguage = settings?.languagePreference ?? "auto";
  const selectedProxyMode = settings?.updateProxyMode ?? "none";
  const updateDownloadProgress =
    updateProgress?.status === "downloading" && typeof updateProgress.progress === "number"
      ? Math.max(0, Math.min(100, updateProgress.progress))
      : null;
  const updateStatusText =
    updateProgress?.status === "checking"
      ? t("settings.update.checkingStatus")
      : updateProgress?.status === "downloading"
        ? updateDownloadProgress === null
          ? t("settings.update.downloadingIndeterminate")
          : t("settings.update.downloading", { progress: updateDownloadProgress })
        : updateProgress?.status === "failed"
          ? t("settings.update.checkFailed", { message: updateProgress.message })
          : "";

  return (
    <main className="settings-shell">
      <aside className="settings-sidebar" aria-label={t("common.settings")}>
        <nav className="settings-nav">
          {sections.map((section) => {
            const Icon = section.icon;
            return (
              <button
                key={section.id}
                type="button"
                className={active === section.id ? "active" : ""}
                onClick={() => selectSection(section.id)}
              >
                <Icon size={17} strokeWidth={2.1} />
                <span>{t(section.labelKey)}</span>
              </button>
            );
          })}
        </nav>
      </aside>

      <section className="settings-content">
        {active === "general" ? (
          <div className="settings-panel">
            <div className="settings-panel-heading">
              <h2>{t("settings.nav.general")}</h2>
              <p>{t("settings.general.detail")}</p>
            </div>
            <div className="settings-group">
              <label className="settings-field">
                <span className="settings-field-label">
                  <Languages size={16} strokeWidth={2.1} />
                  {t("common.language.title")}
                </span>
                <select
                  value={selectedLanguage}
                  disabled={busy === "language"}
                  onChange={(event) => chooseLanguage(event.target.value as LanguagePreference)}
                >
                  <option value="auto">{t("common.language.auto")}</option>
                  <option value="zh-CN">{t("common.language.zhCN")}</option>
                  <option value="en-US">{t("common.language.enUS")}</option>
                </select>
              </label>
              <ToggleRow
                icon={Power}
                label={t("settings.general.autostart")}
                detail={t("settings.general.autostartDetail")}
                checked={autostartEnabled}
                disabled={busy === "autostart"}
                onChange={toggleAutostart}
              />
              <ToggleRow
                icon={Bell}
                label={t("settings.general.notifications")}
                detail={t("settings.general.notificationsDetail")}
                checked={settings?.notificationsEnabled ?? true}
                disabled={busy === "notifications"}
                onChange={(checked) => updateSetting({ notificationsEnabled: checked }, "notifications")}
              />
            </div>
          </div>
        ) : null}

        {active === "lan" ? (
          <div className="settings-panel">
            <div className="settings-panel-heading">
              <h2>{t("settings.nav.lan")}</h2>
              <p>{t("settings.lan.detail")}</p>
            </div>
            <div className="settings-group">
              <ToggleRow
                icon={Router}
                label={t("settings.lan.enabled")}
                detail={t("settings.lan.enabledDetail")}
                checked={settings?.lanEnabled ?? true}
                disabled={busy === "lan"}
                onChange={(checked) => updateSetting({ lanEnabled: checked }, "lan")}
              />
              <label className="settings-field">
                <span className="settings-field-label">{t("settings.lan.port")}</span>
                <div className="settings-inline">
                  <input
                    type="number"
                    min={1}
                    max={65535}
                    value={portValue}
                    disabled={busy === "port"}
                    onChange={(event) => setPortValue(event.target.value)}
                  />
                  <button type="button" className="settings-button" onClick={savePort}>
                    {busy === "port" ? t("settings.saving") : t("settings.save")}
                  </button>
                </div>
              </label>
              <label className="settings-field">
                <span className="settings-field-label">{t("settings.lan.networkInterface")}</span>
                <select
                  value={selectedInterface}
                  disabled={busy === "network"}
                  onChange={(event) =>
                    updateSetting(
                      { networkInterfaceId: event.target.value === "auto" ? null : event.target.value },
                      "network",
                    )
                  }
                >
                  <option value="auto">{t("settings.lan.networkInterfaceAuto")}</option>
                  {interfaces.map((item) => (
                    <option key={item.id} value={item.id}>{item.label}</option>
                  ))}
                </select>
              </label>
            </div>
          </div>
        ) : null}

        {active === "update" ? (
          <div className="settings-panel">
            <div className="settings-panel-heading">
              <h2>{t("settings.nav.update")}</h2>
              <p>{t("settings.update.detail")}</p>
            </div>
            <div className="settings-group">
              <button
                type="button"
                className="settings-button settings-check-button"
                disabled={busy === "checkUpdates"}
                onClick={runUpdateCheck}
              >
                <RefreshCw size={15} strokeWidth={2.1} />
                {busy === "checkUpdates" ? t("settings.update.checking") : t("settings.update.check")}
              </button>
              {updateStatusText ? (
                <div className="settings-update-progress">
                  <div className="settings-update-status">
                    <span>{updateStatusText}</span>
                    {updateProgress?.status === "failed" ? (
                      <button
                        type="button"
                        className="settings-button settings-secondary-button"
                        disabled={busy === "checkUpdates"}
                        onClick={runUpdateCheck}
                      >
                        {t("settings.update.retry")}
                      </button>
                    ) : null}
                  </div>
                  {updateProgress?.status === "downloading" ? (
                    <div className="settings-progress-track" aria-hidden="true">
                      <span
                        className={updateDownloadProgress === null ? "indeterminate" : ""}
                        style={updateDownloadProgress === null ? undefined : { width: `${updateDownloadProgress}%` }}
                      />
                    </div>
                  ) : null}
                </div>
              ) : null}
              <div className="settings-field settings-proxy-mode-field">
                <span className="settings-field-label">
                  <Globe2 size={16} strokeWidth={2.1} />
                  {t("settings.update.proxy")}
                </span>
                <div className="settings-field-stack">
                  <div className="settings-segmented" role="group" aria-label={t("settings.update.proxy")}>
                    {updateProxyModes.map((mode) => (
                      <button
                        key={mode.value}
                        type="button"
                        className={selectedProxyMode === mode.value ? "active" : ""}
                        disabled={busy === "proxyMode"}
                        onClick={() => chooseProxyMode(mode.value)}
                      >
                        {t(mode.labelKey)}
                      </button>
                    ))}
                  </div>
                  <small>{t("settings.update.proxyDetail")}</small>
                </div>
              </div>
              {settings?.updateProxyMode === "manual" ? (
                <label className="settings-field">
                  <span className="settings-field-label">{t("settings.update.proxyUrl")}</span>
                  <div className="settings-inline">
                    <input
                      value={proxyUrl}
                      placeholder="http://127.0.0.1:7890"
                      disabled={busy === "proxyUrl"}
                      onChange={(event) => setProxyUrl(event.target.value)}
                    />
                    <button type="button" className="settings-button" onClick={saveProxy}>
                      {busy === "proxyUrl" ? t("settings.saving") : t("settings.save")}
                    </button>
                  </div>
                </label>
              ) : null}
            </div>
          </div>
        ) : null}

        {active === "about" ? (
          <div className="settings-panel">
            <div className="settings-panel-heading">
              <h2>{t("settings.nav.about")}</h2>
              <p>{t("settings.about.detail")}</p>
            </div>
            <div className="settings-about-card">
              <span className="settings-about-icon">
                <img className="settings-about-app-icon" src={appIcon} alt="" />
              </span>
              <div>
                <strong>{t("app.name")}</strong>
                <span>{t("settings.about.version", { version: version || "-" })}</span>
              </div>
            </div>
          </div>
        ) : null}

        {notice ? (
          <p className="settings-notice">
            <Check size={14} strokeWidth={2.1} />
            {notice}
          </p>
        ) : null}
        {error ? <p className="settings-error" role="alert">{error}</p> : null}
      </section>
    </main>
  );
}
