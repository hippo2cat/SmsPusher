export type PairingCode = {
  value: string;
  sessionId: string;
  expiresAt: string;
};

export type DeviceSnapshot = {
  deviceId: string;
  deviceName: string;
  revoked: boolean;
  secureTransportVersion?: number | null;
  keyId?: string | null;
};

export type MessageSnapshot = {
  messageId: string;
  sender: string;
  body: string;
  receivedAt: string;
  subscriptionId: number;
  deviceId: string;
  verificationCode?: string | null;
};

export type TransportSnapshot = {
  lanPort?: number | null;
  mdnsServiceType: string;
  status: string;
  secureProtocol?: string | null;
};

export type LanguagePreference = "auto" | "zh-CN" | "en-US";

export type AppSettingsSnapshot = {
  preferredPort: number;
  historyLimit: number;
  lanEnabled: boolean;
  notificationsEnabled: boolean;
  networkInterfaceId?: string | null;
  languagePreference: LanguagePreference;
};

export type StatusSnapshot = {
  serviceName: string;
  preferredPort: number;
  pairingCode: PairingCode;
  devices: DeviceSnapshot[];
  latestMessages: MessageSnapshot[];
  transport: TransportSnapshot;
  settings?: AppSettingsSnapshot;
};

export type NetworkInterfaceSnapshot = {
  id: string;
  name: string;
  ipv4: string;
  label: string;
};

export type LanDiagnosticWarningKind = "windowsFirewall" | "staleNetworkInterface";

export type LanDiagnosticWarning = {
  kind: LanDiagnosticWarningKind;
  port?: number | null;
};

export type LanDiagnosticsSnapshot = {
  warnings: LanDiagnosticWarning[];
};

export type AppSettingsUpdate = {
  preferredPort?: number;
  historyLimit?: number;
  lanEnabled?: boolean;
  notificationsEnabled?: boolean;
  networkInterfaceId?: string | null;
  languagePreference?: LanguagePreference;
};
