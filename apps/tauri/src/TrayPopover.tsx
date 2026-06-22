import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { CSSProperties } from "react";
import { getCurrentWindow, LogicalSize } from "@tauri-apps/api/window";
import {
  ChevronRight,
  Circle,
  History,
  Link2Off,
  Power,
  RefreshCw,
  Settings,
  ShieldAlert,
  Smartphone,
} from "lucide-react";
import { useTranslation } from "react-i18next";
import { changeAppLanguage, resolveLocale } from "./i18n";
import {
  getAutostartEnabled,
  getLanDiagnostics,
  getSettings,
  getStatus,
  hideTrayPopover,
  listNetworkInterfaces,
  listenToServiceEvents,
  openHistoryFromTray,
  quitApp,
  refreshPairingCode,
  revokeDevice,
  setAutostartEnabled,
  updateSettings,
} from "./tauri";
import type {
  AppSettingsSnapshot,
  DeviceSnapshot,
  LanDiagnosticsSnapshot,
  LanguagePreference,
  NetworkInterfaceSnapshot,
  StatusSnapshot,
} from "./types";

const TRAY_POPOVER_WIDTH = 376;
const MIN_TRAY_POPOVER_HEIGHT = 1;

function remainingSeconds(expiresAt: string, now: Date) {
  return Math.max(0, Math.ceil((new Date(expiresAt).getTime() - now.getTime()) / 1000));
}

function formatRemaining(seconds: number) {
  const minutes = Math.floor(seconds / 60);
  const rest = seconds % 60;
  return `${String(minutes).padStart(2, "0")}:${String(rest).padStart(2, "0")}`;
}

function formatPairingCode(value?: string) {
  const normalized = value?.replace(/\D/g, "") ?? "";
  if (normalized.length !== 6) return value || "--- ---";
  return `${normalized.slice(0, 3)} ${normalized.slice(3)}`;
}

function activeDevices(devices: DeviceSnapshot[]) {
  return devices.filter((device) => !device.revoked);
}

export default function TrayPopover() {
  const { t, i18n } = useTranslation();
  const rootRef = useRef<HTMLDivElement>(null);
  const codeRef = useRef<HTMLDivElement>(null);
  const settingsRef = useRef<HTMLDivElement>(null);
  const codeAnimationRef = useRef<Animation | null>(null);
  const settingsAnimationRef = useRef<Animation | null>(null);
  const [status, setStatus] = useState<StatusSnapshot | null>(null);
  const [settings, setSettings] = useState<AppSettingsSnapshot | null>(null);
  const [lanDiagnostics, setLanDiagnostics] = useState<LanDiagnosticsSnapshot>({ warnings: [] });
  const [interfaces, setInterfaces] = useState<NetworkInterfaceSnapshot[]>([]);
  const [now, setNow] = useState(() => new Date());
  const [error, setError] = useState("");
  const [busy, setBusy] = useState<string | null>(null);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [autostartEnabled, setAutostartEnabledState] = useState(false);

  const resizeTrayPopover = useCallback((targetHeight?: number) => {
    window.requestAnimationFrame(() => {
      const root = rootRef.current;
      if (!root) return;
      const height = Math.max(
        MIN_TRAY_POPOVER_HEIGHT,
        Math.ceil(targetHeight ?? root.getBoundingClientRect().height),
      );
      getCurrentWindow()
        .setSize(new LogicalSize(TRAY_POPOVER_WIDTH, height))
        .catch((resizeError) => setError(String(resizeError)));
    });
  }, []);

  const load = useCallback(async () => {
    const [
      nextStatus,
      nextSettings,
      nextLanDiagnostics,
      nextInterfaces,
      nextAutostartEnabled,
    ] = await Promise.all([
      getStatus(),
      getSettings(),
      getLanDiagnostics(),
      listNetworkInterfaces(),
      getAutostartEnabled(),
    ]);
    setStatus(nextStatus);
    setSettings(nextSettings);
    setLanDiagnostics(nextLanDiagnostics);
    setInterfaces(nextInterfaces);
    setAutostartEnabledState(nextAutostartEnabled);
    setError("");
  }, []);

  useEffect(() => {
    load().catch((loadError) => setError(String(loadError)));
  }, [load]);

  useEffect(() => {
    const tick = window.setInterval(() => setNow(new Date()), 1000);
    const poll = window.setInterval(() => {
      load().catch((loadError) => setError(String(loadError)));
    }, 5000);
    return () => {
      window.clearInterval(tick);
      window.clearInterval(poll);
    };
  }, [load]);

  useEffect(() => {
    let dispose: (() => void) | undefined;
    listenToServiceEvents(() => {
      load().catch((loadError) => setError(String(loadError)));
    }).then((unlisten) => {
      dispose = unlisten;
    });
    return () => dispose?.();
  }, [load]);

  useEffect(() => {
    resizeTrayPopover();
  }, [status, settings, lanDiagnostics, interfaces, resizeTrayPopover]);

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        hideTrayPopover().catch((hideError) => setError(String(hideError)));
      }
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, []);

  const remaining = status ? remainingSeconds(status.pairingCode.expiresAt, now) : 0;
  const devices = useMemo(() => activeDevices(status?.devices ?? []), [status]);
  const selectedInterface = settings?.networkInterfaceId ?? "auto";
  const selectedLanguage = settings?.languagePreference ?? "auto";
  const countdownProgress = Math.max(0, Math.min(1, remaining / 30));

  useEffect(() => {
    if (!settings?.languagePreference) return;
    if (i18n.language === resolveLocale(settings.languagePreference)) return;
    changeAppLanguage(settings.languagePreference).catch((languageError) => setError(String(languageError)));
  }, [settings?.languagePreference, i18n.language]);

  useEffect(() => {
    if (!codeRef.current) return;
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    codeAnimationRef.current?.cancel();
    codeAnimationRef.current = codeRef.current.animate(
      [
        { transform: "scale(0.992)" },
        { transform: "scale(1)" },
      ],
      {
        duration: 160,
        easing: "cubic-bezier(0.22, 1, 0.36, 1)",
      },
    );
  }, [remaining]);

  useEffect(() => {
    if (!settingsRef.current || !rootRef.current) return;
    const drawer = settingsRef.current;
    const drawerHeight = drawer.getBoundingClientRect().height;
    const targetDrawerHeight = settingsOpen ? drawer.scrollHeight : 0;
    const rootHeight = rootRef.current.getBoundingClientRect().height;
    resizeTrayPopover(rootHeight + targetDrawerHeight - drawerHeight);

    settingsAnimationRef.current?.cancel();

    if (!settingsOpen && drawerHeight === 0) {
      drawer.style.height = "0px";
      drawer.style.opacity = "0";
      drawer.style.visibility = "hidden";
      return;
    }

    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      drawer.style.height = settingsOpen ? "auto" : "0px";
      drawer.style.opacity = settingsOpen ? "1" : "0";
      drawer.style.visibility = settingsOpen ? "visible" : "hidden";
      resizeTrayPopover();
      return;
    }

    drawer.style.height = `${drawerHeight}px`;
    drawer.style.visibility = "visible";
    drawer.style.opacity = settingsOpen ? "0" : "1";

    const animation = drawer.animate(
      [
        { height: `${drawerHeight}px`, opacity: settingsOpen ? 0 : 1 },
        { height: `${targetDrawerHeight}px`, opacity: settingsOpen ? 1 : 0 },
      ],
      {
        duration: 200,
        easing: "cubic-bezier(0.22, 1, 0.36, 1)",
        fill: "forwards",
      },
    );

    settingsAnimationRef.current = animation;
    animation.onfinish = () => {
      drawer.style.height = settingsOpen ? "auto" : "0px";
      drawer.style.opacity = settingsOpen ? "1" : "0";
      drawer.style.visibility = settingsOpen ? "visible" : "hidden";
      resizeTrayPopover();
    };
  }, [settingsOpen, resizeTrayPopover]);

  async function refreshCode() {
    setBusy("refresh");
    try {
      await refreshPairingCode();
      await load();
    } catch (refreshError) {
      setError(String(refreshError));
    } finally {
      setBusy(null);
    }
  }

  async function removeDevice(deviceId: string) {
    setBusy(deviceId);
    try {
      await revokeDevice(deviceId);
      await load();
    } catch (removeError) {
      setError(String(removeError));
    } finally {
      setBusy(null);
    }
  }

  async function chooseInterface(value: string) {
    setBusy("network");
    try {
      await updateSettings({ networkInterfaceId: value === "auto" ? null : value });
      await load();
    } catch (settingsError) {
      setError(String(settingsError));
    } finally {
      setBusy(null);
    }
  }

  async function chooseLanguage(value: LanguagePreference) {
    setBusy("language");
    try {
      const nextSettings = await updateSettings({ languagePreference: value });
      setSettings(nextSettings);
      await changeAppLanguage(nextSettings.languagePreference);
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
      const nextAutostartEnabled = await setAutostartEnabled(value);
      setAutostartEnabledState(nextAutostartEnabled);
      setError("");
    } catch (autostartError) {
      setError(String(autostartError));
    } finally {
      setBusy(null);
    }
  }

  return (
    <main ref={rootRef} className="desktop-popup-canvas">
      <section className="popup-card" aria-label={t("tray.accessibilityLabel")}>
        <div className="popup-scroll">
          <section className="pairing-card stagger-item">
            <div className="pairing-title">
              <span>{t("tray.pairingCode.title")}</span>
              <button
                className="refresh-icon"
                type="button"
                title={t("tray.pairingCode.refresh")}
                disabled={busy === "refresh"}
                onClick={refreshCode}
              >
                <RefreshCw size={14} strokeWidth={2.2} />
              </button>
            </div>
            <div ref={codeRef} className="pairing-number">
              {formatPairingCode(status?.pairingCode.value)}
            </div>
            <div className="countdown-line">
              <span
                className="countdown-ring"
                style={{ "--countdown-progress": countdownProgress } as CSSProperties}
              />
              <span>{t("tray.pairingCode.validFor")}</span>
              <strong>{formatRemaining(remaining)}</strong>
            </div>
          </section>

          <section className="device-area stagger-item">
            <p className="popup-label">{t("tray.devices.title")}</p>
            <div className="paired-device-list">
              {devices.length > 0 ? devices.map((device) => (
                <div className="paired-device-row" key={device.deviceId}>
                  <span className="phone-icon"><Smartphone size={17} strokeWidth={2.1} /></span>
                  <div className="paired-device-copy">
                    <strong>{device.deviceName}</strong>
                    <span><Circle size={7} fill="#00A65A" strokeWidth={0} /> {t("tray.devices.running")}</span>
                  </div>
                  <button
                    className="unlink-button"
                    type="button"
                    title={t("tray.devices.revoke")}
                    disabled={busy === device.deviceId}
                    onClick={() => removeDevice(device.deviceId)}
                  >
                    <Link2Off size={18} strokeWidth={2.1} />
                  </button>
                </div>
              )) : (
                <div className="paired-device-row empty-device">
                  <span className="phone-icon"><Smartphone size={17} strokeWidth={2.1} /></span>
                  <div className="paired-device-copy">
                    <strong>{t("tray.devices.emptyTitle")}</strong>
                    <span><Circle size={7} fill="#C1C6D7" strokeWidth={0} /> {t("tray.devices.waiting")}</span>
                  </div>
                </div>
              )}
            </div>
          </section>

          {lanDiagnostics.warnings.map((warning) => {
            if (warning.kind !== "windowsFirewall") return null;
            return (
              <section className="diagnostic-card stagger-item" key={warning.kind}>
                <span className="diagnostic-icon"><ShieldAlert size={17} strokeWidth={2.1} /></span>
                <div className="diagnostic-copy">
                  <strong>{t("tray.lanDiagnostics.windowsFirewall.title")}</strong>
                  <span>{t("tray.lanDiagnostics.windowsFirewall.detail", { port: warning.port ?? "-" })}</span>
                </div>
              </section>
            );
          })}

          <section className="nav-card stagger-item">
            <button className="popup-nav-row" type="button" onClick={openHistoryFromTray}>
              <span className="nav-left"><History size={18} strokeWidth={2.1} /> {t("tray.history")}</span>
              <ChevronRight size={18} strokeWidth={2.1} />
            </button>
            <div className="popup-divider" />
            <button className="popup-nav-row" type="button" onClick={() => setSettingsOpen((value) => !value)}>
              <span className="nav-left"><Settings size={18} strokeWidth={2.1} /> {t("common.settings")}</span>
              <ChevronRight className={settingsOpen ? "rotated" : ""} size={18} strokeWidth={2.1} />
            </button>
            <div ref={settingsRef} className="settings-drawer" aria-hidden={!settingsOpen}>
              <label className="settings-toggle-row">
                <span>{t("tray.autostart")}</span>
                <input
                  type="checkbox"
                  checked={autostartEnabled}
                  disabled={busy === "autostart"}
                  onChange={(event) => toggleAutostart(event.target.checked)}
                />
              </label>
              <label>
                {t("tray.networkInterface")}
                <select
                  value={selectedInterface}
                  disabled={busy === "network"}
                  onChange={(event) => chooseInterface(event.target.value)}
                >
                  <option value="auto">{t("tray.networkInterface.auto")}</option>
                  {interfaces.map((item) => (
                    <option key={item.id} value={item.id}>{item.label}</option>
                  ))}
                </select>
              </label>
              <label htmlFor="language-select">
                {t("common.language.title")}
                <select
                  id="language-select"
                  value={selectedLanguage}
                  disabled={busy === "language"}
                  onChange={(event) => chooseLanguage(event.target.value as LanguagePreference)}
                >
                  <option value="auto">{t("common.language.auto")}</option>
                  <option value="zh-CN">{t("common.language.zhCN")}</option>
                  <option value="en-US">{t("common.language.enUS")}</option>
                </select>
              </label>
            </div>
          </section>

          {error ? <p className="popup-error" role="alert">{error}</p> : null}
        </div>

        <footer className="popup-footer stagger-item">
          <button type="button" onClick={quitApp}>
            <Power size={16} strokeWidth={2.1} />
            {t("common.exit")}
          </button>
        </footer>
      </section>
    </main>
  );
}
