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
  X,
} from "lucide-react";
import { useTranslation } from "react-i18next";
import { changeAppLanguage, resolveLocale } from "./i18n";
import {
  getLanDiagnostics,
  getSettings,
  getStatus,
  hideTrayPopover,
  listenToServiceEvents,
  listenToTrayVisibility,
  openHistoryFromTray,
  openSettingsFromTray,
  quitApp,
  refreshPairingCode,
  revokeDevice,
} from "./tauri";
import type {
  AppSettingsSnapshot,
  DeviceSnapshot,
  LanDiagnosticsSnapshot,
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
  const codeAnimationRef = useRef<Animation | null>(null);
  const isOpenRef = useRef(false);
  const [status, setStatus] = useState<StatusSnapshot | null>(null);
  const [settings, setSettings] = useState<AppSettingsSnapshot | null>(null);
  const [lanDiagnostics, setLanDiagnostics] = useState<LanDiagnosticsSnapshot>({ warnings: [] });
  const [now, setNow] = useState(() => new Date());
  const [error, setError] = useState("");
  const [busy, setBusy] = useState<string | null>(null);
  const [isOpen, setIsOpen] = useState(false);
  const [
    dismissedStaleNetworkInterfaceWarning,
    setDismissedStaleNetworkInterfaceWarning,
  ] = useState(false);

  const resizeTrayPopover = useCallback((targetHeight?: number) => {
    window.requestAnimationFrame(() => {
      if (!isOpenRef.current) return;
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
    ] = await Promise.all([
      getStatus(),
      getSettings(),
      getLanDiagnostics(),
    ]);
    setStatus(nextStatus);
    setSettings(nextSettings);
    setLanDiagnostics(nextLanDiagnostics);
    setError("");
  }, []);

  useEffect(() => {
    isOpenRef.current = isOpen;
  }, [isOpen]);

  useEffect(() => {
    if (!isOpen) return;
    const tick = window.setInterval(() => setNow(new Date()), 1000);
    const poll = window.setInterval(() => {
      load().catch((loadError) => setError(String(loadError)));
    }, 5000);
    return () => {
      window.clearInterval(tick);
      window.clearInterval(poll);
    };
  }, [isOpen, load]);

  useEffect(() => {
    let dispose: (() => void) | undefined;
    listenToTrayVisibility(
      () => {
        isOpenRef.current = true;
        setIsOpen(true);
        setNow(new Date());
        load().catch((loadError) => setError(String(loadError)));
      },
      () => {
        isOpenRef.current = false;
        setIsOpen(false);
        codeAnimationRef.current?.cancel();
      },
    ).then((unlisten) => {
      dispose = unlisten;
    });
    return () => dispose?.();
  }, [load]);

  useEffect(() => {
    let dispose: (() => void) | undefined;
    listenToServiceEvents(() => {
      if (!isOpenRef.current) return;
      load().catch((loadError) => setError(String(loadError)));
    }).then((unlisten) => {
      dispose = unlisten;
    });
    return () => dispose?.();
  }, [load]);

  useEffect(() => {
    if (!isOpen) return;
    resizeTrayPopover();
  }, [
    isOpen,
    status,
    settings,
    lanDiagnostics,
    dismissedStaleNetworkInterfaceWarning,
    resizeTrayPopover,
  ]);

  useEffect(() => {
    if (lanDiagnostics.warnings.some((warning) => warning.kind === "staleNetworkInterface")) return;
    setDismissedStaleNetworkInterfaceWarning(false);
  }, [lanDiagnostics.warnings]);

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
  const countdownProgress = Math.max(0, Math.min(1, remaining / 30));
  const visibleDiagnosticWarnings = useMemo(
    () =>
      lanDiagnostics.warnings.filter(
        (warning) =>
          warning.kind !== "staleNetworkInterface" || !dismissedStaleNetworkInterfaceWarning,
      ),
    [dismissedStaleNetworkInterfaceWarning, lanDiagnostics.warnings],
  );

  useEffect(() => {
    if (!settings?.languagePreference) return;
    if (i18n.language === resolveLocale(settings.languagePreference)) return;
    changeAppLanguage(settings.languagePreference).catch((languageError) => setError(String(languageError)));
  }, [settings?.languagePreference, i18n.language]);

  useEffect(() => {
    if (!isOpen) return;
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
  }, [isOpen, remaining]);

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

          {visibleDiagnosticWarnings.map((warning) => {
            const content = warning.kind === "windowsFirewall"
              ? {
                title: t("tray.lanDiagnostics.windowsFirewall.title"),
                detail: t("tray.lanDiagnostics.windowsFirewall.detail", { port: warning.port ?? "-" }),
              }
              : {
                title: t("tray.lanDiagnostics.staleNetworkInterface.title"),
                detail: t("tray.lanDiagnostics.staleNetworkInterface.detail"),
              };
            return (
              <section className="diagnostic-card stagger-item" key={warning.kind}>
                <span className="diagnostic-icon"><ShieldAlert size={17} strokeWidth={2.1} /></span>
                <div className="diagnostic-copy">
                  <strong>{content.title}</strong>
                  <span>{content.detail}</span>
                </div>
                {warning.kind === "staleNetworkInterface" ? (
                  <button
                    className="diagnostic-dismiss"
                    type="button"
                    title={t("common.close")}
                    aria-label={t("common.close")}
                    onClick={() => setDismissedStaleNetworkInterfaceWarning(true)}
                  >
                    <X size={13} strokeWidth={2.2} />
                  </button>
                ) : null}
              </section>
            );
          })}

          <section className="nav-card stagger-item">
            <button className="popup-nav-row" type="button" onClick={openHistoryFromTray}>
              <span className="nav-left"><History size={18} strokeWidth={2.1} /> {t("tray.history")}</span>
              <ChevronRight size={18} strokeWidth={2.1} />
            </button>
            <div className="popup-divider" />
            <button className="popup-nav-row" type="button" onClick={openSettingsFromTray}>
              <span className="nav-left"><Settings size={18} strokeWidth={2.1} /> {t("common.settings")}</span>
              <ChevronRight size={18} strokeWidth={2.1} />
            </button>
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
