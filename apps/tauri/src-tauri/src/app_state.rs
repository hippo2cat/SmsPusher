use crate::{
    i18n::LanguagePreference,
    keyring_secret_store::{AppSecretStore, LOCAL_SECRET_FILE_NAME},
    storage,
};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use smspusher_core::{FileDeviceStore, SqliteMessageStore, TokenGenerator};
use smspusher_service::{
    DesktopService, DesktopServiceConfig, DeviceSnapshot, MessageSnapshot, ServiceEvent,
    StatusSnapshot, TransportSnapshot, VecEventSink,
};
use std::{
    env,
    net::Ipv4Addr,
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
};
use transport_lan::{
    advertised_ipv4_for_interface, network_interface_candidates, shared_lan_service,
    LanNetworkInterface, LanServer, LanServerConfig, RunningLanServer, SharedLanService,
};

type Clock = fn() -> DateTime<Utc>;
type AppServiceHandle =
    SharedLanService<FileDeviceStore, SqliteMessageStore, VecEventSink, Clock, AppSecretStore>;
type NetworkInterfacesProvider = Arc<dyn Fn() -> Vec<LanNetworkInterface> + Send + Sync>;

const DEFAULT_SERVICE_NAME: &str = "SmsPusher";

fn now_utc() -> DateTime<Utc> {
    Utc::now()
}

pub fn desktop_service_name_from_candidates<I, S>(candidates: I) -> String
where
    I: IntoIterator<Item = S>,
    S: AsRef<str>,
{
    candidates
        .into_iter()
        .filter_map(|candidate| sanitized_desktop_service_name(candidate.as_ref()))
        .next()
        .unwrap_or_else(|| DEFAULT_SERVICE_NAME.to_owned())
}

pub fn default_desktop_service_name() -> String {
    let mut candidates = system_desktop_service_name_candidates();
    candidates.extend(
        ["COMPUTERNAME", "HOSTNAME"]
            .into_iter()
            .filter_map(|key| env::var(key).ok()),
    );
    desktop_service_name_from_candidates(candidates)
}

fn sanitized_desktop_service_name(value: &str) -> Option<String> {
    let trimmed = value.trim();
    if trimmed.is_empty() {
        return None;
    }
    Some(trimmed.to_owned())
}

#[cfg(target_os = "macos")]
fn system_desktop_service_name_candidates() -> Vec<String> {
    ["ComputerName", "LocalHostName"]
        .into_iter()
        .filter_map(read_scutil_value)
        .collect()
}

#[cfg(not(target_os = "macos"))]
fn system_desktop_service_name_candidates() -> Vec<String> {
    Vec::new()
}

#[cfg(target_os = "macos")]
fn read_scutil_value(key: &str) -> Option<String> {
    let output = std::process::Command::new("/usr/sbin/scutil")
        .args(["--get", key])
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    String::from_utf8(output.stdout).ok()
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum UpdateProxyMode {
    None,
    System,
    Manual,
}

impl Default for UpdateProxyMode {
    fn default() -> Self {
        Self::None
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AppSettingsSnapshot {
    pub preferred_port: u16,
    pub history_limit: usize,
    pub lan_enabled: bool,
    pub notifications_enabled: bool,
    pub network_interface_id: Option<String>,
    #[serde(default)]
    pub language_preference: LanguagePreference,
    #[serde(default)]
    pub update_proxy_mode: UpdateProxyMode,
    #[serde(default, rename = "updateProxyEnabled", skip_serializing)]
    legacy_update_proxy_enabled: bool,
    #[serde(default)]
    pub update_proxy_url: String,
}

impl AppSettingsSnapshot {
    pub fn normalize_legacy_settings(&mut self) {
        if self.legacy_update_proxy_enabled && self.update_proxy_mode == UpdateProxyMode::None {
            self.update_proxy_mode = UpdateProxyMode::Manual;
        }
        self.legacy_update_proxy_enabled = false;
    }
}

impl Default for AppSettingsSnapshot {
    fn default() -> Self {
        Self {
            preferred_port: 55515,
            history_limit: 1000,
            lan_enabled: true,
            notifications_enabled: true,
            network_interface_id: None,
            language_preference: LanguagePreference::Auto,
            update_proxy_mode: UpdateProxyMode::None,
            legacy_update_proxy_enabled: false,
            update_proxy_url: String::new(),
        }
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AppSettingsUpdate {
    pub preferred_port: Option<u16>,
    pub history_limit: Option<usize>,
    pub lan_enabled: Option<bool>,
    pub notifications_enabled: Option<bool>,
    #[serde(default, deserialize_with = "deserialize_network_interface_update")]
    pub network_interface_id: Option<Option<String>>,
    pub language_preference: Option<LanguagePreference>,
    pub update_proxy_mode: Option<UpdateProxyMode>,
    pub update_proxy_url: Option<String>,
}

fn deserialize_network_interface_update<'de, D>(
    deserializer: D,
) -> Result<Option<Option<String>>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    Option::<String>::deserialize(deserializer).map(Some)
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct QueueSnapshot {
    pub pending: usize,
}

#[derive(Debug, thiserror::Error)]
pub enum AppStateError {
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),
    #[error("lan error: {0}")]
    Lan(String),
    #[error("service error: {0}")]
    Service(#[from] smspusher_service::ServiceError),
    #[error("message store error: {0}")]
    MessageStore(#[from] smspusher_core::MessageStoreError),
    #[error("storage error: {0}")]
    Storage(#[from] storage::StorageError),
}

pub struct SmsPusherAppState {
    service: AppServiceHandle,
    events: VecEventSink,
    settings: Mutex<AppSettingsSnapshot>,
    data_dir: PathBuf,
    lan_server: Mutex<Option<RunningLanServer>>,
    last_advertised_ipv4: Mutex<Option<Ipv4Addr>>,
    publish_bonjour: bool,
    service_name: String,
    network_interfaces: NetworkInterfacesProvider,
}

impl SmsPusherAppState {
    pub fn new_for_data_dir(path: impl AsRef<Path>) -> Result<Self, AppStateError> {
        let data_dir = path.as_ref().to_path_buf();
        Self::new_for_data_dir_with_secret_store(
            &data_dir,
            true,
            AppSecretStore::local_file(data_dir.join(LOCAL_SECRET_FILE_NAME)),
            Arc::new(network_interface_candidates),
        )
    }

    pub fn new_for_data_dir_with_bonjour(
        path: impl AsRef<Path>,
        publish_bonjour: bool,
    ) -> Result<Self, AppStateError> {
        let data_dir = path.as_ref().to_path_buf();
        Self::new_for_data_dir_with_secret_store(
            &data_dir,
            publish_bonjour,
            AppSecretStore::local_file(data_dir.join(LOCAL_SECRET_FILE_NAME)),
            Arc::new(network_interface_candidates),
        )
    }

    pub fn new_for_data_dir_with_network_interfaces(
        path: impl AsRef<Path>,
        publish_bonjour: bool,
        network_interfaces: NetworkInterfacesProvider,
    ) -> Result<Self, AppStateError> {
        let data_dir = path.as_ref().to_path_buf();
        Self::new_for_data_dir_with_secret_store(
            &data_dir,
            publish_bonjour,
            AppSecretStore::local_file(data_dir.join(LOCAL_SECRET_FILE_NAME)),
            network_interfaces,
        )
    }

    fn new_for_data_dir_with_secret_store(
        path: impl AsRef<Path>,
        publish_bonjour: bool,
        secret_store: AppSecretStore,
        network_interfaces: NetworkInterfacesProvider,
    ) -> Result<Self, AppStateError> {
        let data_dir = path.as_ref().to_path_buf();
        storage::prepare_sms_pusher_data_dir(&data_dir)?;
        let settings = storage::load_settings(&data_dir)?;
        let events = VecEventSink::default();
        let service_name = default_desktop_service_name();
        tracing::info!(
            data_dir = %data_dir.display(),
            service_name = %service_name,
            preferred_port = settings.preferred_port,
            lan_enabled = settings.lan_enabled,
            "creating desktop service state"
        );
        let service = DesktopService::new_for_tests_with_secrets(
            DesktopServiceConfig {
                service_name: service_name.clone(),
                preferred_port: settings.preferred_port,
                history_limit: settings.history_limit,
            },
            FileDeviceStore::new(data_dir.join("devices.json")),
            SqliteMessageStore::open(data_dir.join("messages.sqlite"), settings.history_limit)?,
            secret_store,
            TokenGenerator::new(),
            events.clone(),
            now_utc as Clock,
        );
        Ok(Self {
            service: shared_lan_service(service),
            events,
            settings: Mutex::new(settings),
            data_dir,
            lan_server: Mutex::new(None),
            last_advertised_ipv4: Mutex::new(None),
            publish_bonjour,
            service_name,
            network_interfaces,
        })
    }

    pub fn data_dir(&self) -> PathBuf {
        self.data_dir.clone()
    }

    pub fn get_status(&self) -> Result<StatusSnapshot, AppStateError> {
        let settings = self.settings();
        let service = self.service.lock().expect("app service lock");
        let mut status = service.status_snapshot();
        status.preferred_port = settings.preferred_port;
        status.latest_messages = service.list_messages(settings.history_limit)?;
        Ok(status)
    }

    pub fn list_devices(&self) -> Result<Vec<DeviceSnapshot>, AppStateError> {
        Ok(self
            .service
            .lock()
            .expect("app service lock")
            .list_devices())
    }

    pub fn list_messages(&self) -> Result<Vec<MessageSnapshot>, AppStateError> {
        let limit = self.settings.lock().expect("settings lock").history_limit;
        Ok(self
            .service
            .lock()
            .expect("app service lock")
            .list_messages(limit)?)
    }

    pub fn refresh_pairing_code(&self) -> Result<String, AppStateError> {
        let value = self
            .service
            .lock()
            .expect("app service lock")
            .refresh_pairing_code();
        tracing::info!("pairing code refreshed from app state");
        Ok(value)
    }

    pub fn revoke_device(&self, device_id: &str) -> Result<StatusSnapshot, AppStateError> {
        tracing::info!(device_id = %device_id, "revoking paired device");
        self.service
            .lock()
            .expect("app service lock")
            .revoke_device(device_id);
        self.get_status()
    }

    pub fn retry_queue(&self) -> QueueSnapshot {
        QueueSnapshot { pending: 0 }
    }

    pub fn test_transport(&self) -> Result<TransportSnapshot, AppStateError> {
        Ok(self.get_status()?.transport)
    }

    pub fn settings(&self) -> AppSettingsSnapshot {
        self.settings.lock().expect("settings lock").clone()
    }

    pub fn update_settings(
        &self,
        update: AppSettingsUpdate,
    ) -> Result<AppSettingsSnapshot, AppStateError> {
        let mut settings = self.settings.lock().expect("settings lock");
        let mut updated = settings.clone();
        if let Some(port) = update.preferred_port {
            updated.preferred_port = port;
        }
        if let Some(limit) = update.history_limit {
            updated.history_limit = limit;
        }
        if let Some(enabled) = update.lan_enabled {
            updated.lan_enabled = enabled;
        }
        if let Some(enabled) = update.notifications_enabled {
            updated.notifications_enabled = enabled;
        }
        if let Some(network_interface_id) = update.network_interface_id {
            updated.network_interface_id = network_interface_id;
        }
        if let Some(language_preference) = update.language_preference {
            updated.language_preference = language_preference;
        }
        if let Some(mode) = update.update_proxy_mode {
            updated.update_proxy_mode = mode;
        }
        if let Some(url) = update.update_proxy_url {
            updated.update_proxy_url = url.trim().to_owned();
        }
        tracing::info!(
            before_port = settings.preferred_port,
            after_port = updated.preferred_port,
            before_lan_enabled = settings.lan_enabled,
            after_lan_enabled = updated.lan_enabled,
            before_interface = ?settings.network_interface_id,
            after_interface = ?updated.network_interface_id,
            notifications_enabled = updated.notifications_enabled,
            language_preference = ?updated.language_preference,
            update_proxy_mode = ?updated.update_proxy_mode,
            "desktop settings updated"
        );
        storage::save_settings(&self.data_dir, &updated)?;
        *settings = updated.clone();
        Ok(updated)
    }

    pub fn network_interfaces(&self) -> Vec<LanNetworkInterface> {
        (self.network_interfaces)()
    }

    pub fn saved_network_interface_is_stale(&self) -> bool {
        let Some(selected_id) = self.settings().network_interface_id else {
            return false;
        };
        !self
            .network_interfaces()
            .iter()
            .any(|interface| interface.id == selected_id)
    }

    pub fn last_advertised_ipv4(&self) -> Option<Ipv4Addr> {
        *self
            .last_advertised_ipv4
            .lock()
            .expect("advertised ip lock")
    }

    fn advertised_ipv4_for_settings(&self, settings: &AppSettingsSnapshot) -> Option<Ipv4Addr> {
        advertised_ipv4_for_interface(
            self.network_interfaces(),
            settings.network_interface_id.as_deref(),
        )
    }

    fn advertised_ipv4_from_interfaces(
        &self,
        settings: &AppSettingsSnapshot,
        interfaces: Vec<LanNetworkInterface>,
    ) -> Option<Ipv4Addr> {
        advertised_ipv4_for_interface(interfaces, settings.network_interface_id.as_deref())
    }

    pub async fn start_lan_server(&self) -> Result<TransportSnapshot, AppStateError> {
        if self.lan_server.lock().expect("lan server lock").is_some() {
            tracing::info!("LAN server start skipped because server is already running");
            return Ok(self.get_status()?.transport);
        }
        let settings = self.settings();
        let interfaces = self.network_interfaces();
        let advertised_ipv4 = self.advertised_ipv4_from_interfaces(&settings, interfaces.clone());
        tracing::info!(
            selected_interface = ?settings.network_interface_id,
            interfaces = ?interfaces,
            advertised_ipv4 = ?advertised_ipv4,
            "resolved LAN advertised IPv4"
        );
        tracing::info!(
            host = "0.0.0.0",
            preferred_port = settings.preferred_port,
            service_name = %self.service_name,
            publish_bonjour = self.publish_bonjour,
            advertised_ipv4 = ?advertised_ipv4,
            "starting LAN server"
        );
        let running = LanServer::start_shared(
            LanServerConfig {
                host: "0.0.0.0".into(),
                preferred_port: settings.preferred_port,
                service_name: self.service_name.clone(),
                pairing_enabled: true,
                publish_bonjour: self.publish_bonjour,
                advertised_ipv4,
            },
            self.service.clone(),
        )
        .await
        .map_err(|error| AppStateError::Lan(error.to_string()))?;
        let transport = self.get_status()?.transport;
        tracing::info!(
            lan_port = ?transport.lan_port,
            status = %transport.status,
            "LAN server started"
        );
        *self.lan_server.lock().expect("lan server lock") = Some(running);
        *self
            .last_advertised_ipv4
            .lock()
            .expect("advertised ip lock") = advertised_ipv4;
        Ok(transport)
    }

    pub async fn stop_lan_server(&self) -> Result<TransportSnapshot, AppStateError> {
        let running = {
            let mut server = self.lan_server.lock().expect("lan server lock");
            server.take()
        };
        if let Some(running) = running {
            tracing::info!(port = running.port(), "stopping LAN server");
            running.shutdown().await;
            tracing::info!("LAN server stopped");
        }
        self.service
            .lock()
            .expect("app service lock")
            .set_lan_port(None);
        *self
            .last_advertised_ipv4
            .lock()
            .expect("advertised ip lock") = None;
        Ok(self.get_status()?.transport)
    }

    pub async fn refresh_lan_advertisement_if_needed(&self) -> Result<(), AppStateError> {
        let settings = self.settings();
        if !settings.lan_enabled {
            return Ok(());
        }
        let next = self.advertised_ipv4_for_settings(&settings);
        let current = self.last_advertised_ipv4();
        let running = self.lan_server.lock().expect("lan server lock").is_some();
        if !running {
            tracing::info!("LAN advertised IPv4 refresh starting stopped LAN server");
            self.start_lan_server().await?;
        } else if current != next {
            tracing::info!(
                before = ?current,
                after = ?next,
                "LAN advertised IPv4 changed; restarting LAN server"
            );
            self.stop_lan_server().await?;
            self.start_lan_server().await?;
        }
        Ok(())
    }

    pub async fn apply_lan_settings(
        &self,
        before: AppSettingsSnapshot,
    ) -> Result<(), AppStateError> {
        let after = self.settings();
        if !after.lan_enabled {
            tracing::info!("applying LAN settings: disabling LAN server");
            self.stop_lan_server().await?;
            return Ok(());
        }
        if !before.lan_enabled
            || before.preferred_port != after.preferred_port
            || before.network_interface_id != after.network_interface_id
        {
            tracing::info!(
                before_lan_enabled = before.lan_enabled,
                after_lan_enabled = after.lan_enabled,
                before_port = before.preferred_port,
                after_port = after.preferred_port,
                before_interface = ?before.network_interface_id,
                after_interface = ?after.network_interface_id,
                "applying LAN settings: restarting LAN server"
            );
            self.stop_lan_server().await?;
            self.start_lan_server().await?;
        }
        Ok(())
    }

    pub fn drain_events(&self) -> Vec<ServiceEvent> {
        self.events.drain()
    }
}
