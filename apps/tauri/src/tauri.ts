import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import {
  disable as disableAutostart,
  enable as enableAutostart,
  isEnabled as isAutostartEnabled,
} from "@tauri-apps/plugin-autostart";
import type {
  AppSettingsSnapshot,
  AppSettingsUpdate,
  DeviceSnapshot,
  LanDiagnosticsSnapshot,
  MessageSnapshot,
  NetworkInterfaceSnapshot,
  StatusSnapshot,
  TransportSnapshot,
} from "./types";

export function getStatus() {
  return invoke<StatusSnapshot>("get_status");
}

export function getSettings() {
  return invoke<AppSettingsSnapshot>("get_settings");
}

export function getLanDiagnostics() {
  return invoke<LanDiagnosticsSnapshot>("get_lan_diagnostics");
}

export function listDevices() {
  return invoke<DeviceSnapshot[]>("list_devices");
}

export function listMessages() {
  return invoke<MessageSnapshot[]>("list_messages");
}

export function listNetworkInterfaces() {
  return invoke<NetworkInterfaceSnapshot[]>("list_network_interfaces");
}

export function refreshPairingCode() {
  return invoke<string>("refresh_pairing_code");
}

export function revokeDevice(deviceId: string) {
  return invoke<StatusSnapshot>("revoke_device", { deviceId });
}

export function testTransport() {
  return invoke<TransportSnapshot>("test_transport");
}

export function updateSettings(update: AppSettingsUpdate) {
  return invoke<AppSettingsSnapshot>("update_settings", { update });
}

export function getAutostartEnabled() {
  return isAutostartEnabled();
}

export async function setAutostartEnabled(enabled: boolean) {
  if (enabled) {
    await enableAutostart();
  } else {
    await disableAutostart();
  }
  return isAutostartEnabled();
}

export function hideTrayPopover() {
  return invoke<void>("hide_tray_popover");
}

export function openHistoryFromTray() {
  return invoke<void>("open_history_from_tray");
}

export function quitApp() {
  return invoke<void>("quit_app");
}

export function listenToTrayVisibility(onOpened: () => void, onHidden: () => void) {
  return Promise.all([
    listen("tray_popover_opened", onOpened),
    listen("tray_popover_hidden", onHidden),
  ]).then((unlisteners) => () => {
    for (const unlisten of unlisteners) {
      unlisten();
    }
  });
}

export function listenToServiceEvents(callback: () => void) {
  return Promise.all([
    listen("status_changed", callback),
    listen("pairing_code_changed", callback),
    listen("device_changed", callback),
    listen("message_received", callback),
    listen("queue_changed", callback),
    listen("transport_changed", callback),
  ]).then((unlisteners) => () => {
    for (const unlisten of unlisteners) {
      unlisten();
    }
  });
}
