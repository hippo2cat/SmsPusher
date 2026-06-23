use anyhow::Result;
use if_addrs::{get_if_addrs, IfAddr};
use mdns_sd::{ServiceDaemon, ServiceInfo};
use std::{
    collections::{BTreeMap, HashMap},
    net::{IpAddr, Ipv4Addr},
};

pub const SMSPUSHER_SERVICE_TYPE: &str = "_smspusher._tcp.local.";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BonjourTxtRecord {
    pub service_type: String,
    pub instance_name: String,
    pub properties: BTreeMap<String, String>,
}

impl BonjourTxtRecord {
    pub fn new(device_name: impl Into<String>, pairing_enabled: bool) -> Self {
        Self::secure(
            device_name,
            pairing_enabled,
            "",
            chrono::DateTime::<chrono::Utc>::from(std::time::SystemTime::UNIX_EPOCH),
        )
    }

    pub fn secure(
        device_name: impl Into<String>,
        pairing_enabled: bool,
        pairing_session_id: impl Into<String>,
        pairing_expires_at: chrono::DateTime<chrono::Utc>,
    ) -> Self {
        let device_name = device_name.into();
        let properties = BTreeMap::from([
            ("version".to_owned(), "2".to_owned()),
            ("secure".to_owned(), "v2".to_owned()),
            ("pake".to_owned(), smspusher_crypto::PAKE_NAME.to_owned()),
            ("deviceName".to_owned(), device_name.clone()),
            ("pairing".to_owned(), pairing_enabled.to_string()),
            ("pairingSessionId".to_owned(), pairing_session_id.into()),
            (
                "pairingExpiresAt".to_owned(),
                pairing_expires_at.to_rfc3339(),
            ),
        ]);

        Self {
            service_type: SMSPUSHER_SERVICE_TYPE.to_owned(),
            instance_name: device_name,
            properties,
        }
    }

    pub fn properties_for_mdns(&self, advertised_ipv4: Ipv4Addr) -> HashMap<String, String> {
        let mut properties: HashMap<String, String> = self
            .properties
            .iter()
            .map(|(key, value)| (key.clone(), value.clone()))
            .collect();
        properties.insert("ipv4".to_owned(), advertised_ipv4.to_string());
        properties
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LanNetworkInterface {
    pub id: String,
    pub name: String,
    pub ipv4: Ipv4Addr,
}

impl LanNetworkInterface {
    pub fn new(name: impl Into<String>, ipv4: Ipv4Addr) -> Self {
        let name = name.into();
        Self {
            id: network_interface_id(&name, ipv4),
            name,
            ipv4,
        }
    }

    pub fn label(&self) -> String {
        format!("{} · {}", self.name, self.ipv4)
    }
}

fn network_interface_id(name: &str, ipv4: Ipv4Addr) -> String {
    format!("{name}@{ipv4}")
}

pub fn preferred_advertised_ipv4<I>(addresses: I) -> Option<Ipv4Addr>
where
    I: IntoIterator<Item = Ipv4Addr>,
{
    addresses.into_iter().find(|address| {
        !address.is_unspecified() && !address.is_loopback() && !address.is_link_local()
    })
}

pub fn advertised_ipv4_for_interface<I>(
    interfaces: I,
    selected_id: Option<&str>,
) -> Option<Ipv4Addr>
where
    I: IntoIterator<Item = LanNetworkInterface>,
{
    let interfaces: Vec<LanNetworkInterface> = interfaces.into_iter().collect();
    if let Some(selected_id) = selected_id {
        if let Some(selected) = interfaces
            .iter()
            .find(|interface| interface.id == selected_id)
        {
            return Some(selected.ipv4);
        }
        let selected_name = selected_id
            .split_once('@')
            .map(|(name, _)| name)
            .unwrap_or(selected_id);
        if let Some(selected) = interfaces
            .iter()
            .find(|interface| interface.name == selected_name)
        {
            return Some(selected.ipv4);
        }
    }
    preferred_advertised_ipv4(interfaces.into_iter().map(|interface| interface.ipv4))
}

pub fn network_interface_candidates() -> Vec<LanNetworkInterface> {
    let mut candidates: Vec<LanNetworkInterface> = get_if_addrs()
        .map(|interfaces| {
            interfaces
                .into_iter()
                .filter_map(|interface| match interface.addr {
                    IfAddr::V4(address) => {
                        Some(LanNetworkInterface::new(interface.name, address.ip))
                    }
                    IfAddr::V6(_) => None,
                })
                .filter(|interface| {
                    !interface.ipv4.is_unspecified()
                        && !interface.ipv4.is_loopback()
                        && !interface.ipv4.is_link_local()
                })
                .collect()
        })
        .unwrap_or_default();
    candidates.sort_by(|left: &LanNetworkInterface, right: &LanNetworkInterface| {
        left.name
            .cmp(&right.name)
            .then_with(|| left.ipv4.cmp(&right.ipv4))
    });
    candidates
}

fn default_advertised_ipv4() -> Option<Ipv4Addr> {
    advertised_ipv4_for_interface(network_interface_candidates(), None)
}

pub trait BonjourPublisher {
    fn publish(
        &mut self,
        record: BonjourTxtRecord,
        port: u16,
        advertised_ipv4: Option<Ipv4Addr>,
    ) -> Result<()>;
    fn stop(&mut self);
}

#[derive(Default)]
pub struct MdnsBonjourPublisher {
    daemon: Option<ServiceDaemon>,
    fullname: Option<String>,
}

impl BonjourPublisher for MdnsBonjourPublisher {
    fn publish(
        &mut self,
        record: BonjourTxtRecord,
        port: u16,
        advertised_ipv4: Option<Ipv4Addr>,
    ) -> Result<()> {
        self.stop();

        tracing::info!(
            instance_name = %record.instance_name,
            service_type = %record.service_type,
            port,
            advertised_ipv4 = ?advertised_ipv4,
            "publishing Bonjour service"
        );
        let advertised_ip = match advertised_ipv4.or_else(default_advertised_ipv4) {
            Some(advertised_ip) => advertised_ip,
            None => {
                tracing::warn!(
                    "no non-loopback IPv4 address available for Bonjour publish; skipping"
                );
                return Ok(());
            }
        };
        let daemon = ServiceDaemon::new().map_err(|error| {
            tracing::warn!(error = %error, "failed to create Bonjour daemon");
            error
        })?;
        let service_info = ServiceInfo::new(
            &record.service_type,
            &record.instance_name,
            "smspusher.local.",
            IpAddr::V4(advertised_ip),
            port,
            record.properties_for_mdns(advertised_ip),
        )?
        .enable_addr_auto();
        let fullname = service_info.get_fullname().to_owned();

        daemon.register(service_info).map_err(|error| {
            tracing::warn!(
                fullname = %fullname,
                error = %error,
                "failed to register Bonjour service"
            );
            error
        })?;
        self.daemon = Some(daemon);
        self.fullname = Some(fullname);
        tracing::info!(advertised_ip = %advertised_ip, "Bonjour service published");
        Ok(())
    }

    fn stop(&mut self) {
        if let (Some(daemon), Some(fullname)) = (&self.daemon, &self.fullname) {
            if let Err(error) = daemon.unregister(fullname) {
                tracing::warn!(fullname = %fullname, error = %error, "failed to unregister Bonjour service");
            } else {
                tracing::info!(fullname = %fullname, "Bonjour service unregistered");
            }
        }
        if let Some(daemon) = &self.daemon {
            if let Err(error) = daemon.shutdown() {
                tracing::warn!(error = %error, "failed to shutdown Bonjour daemon");
            }
        }
        self.fullname = None;
        self.daemon = None;
    }
}

impl Drop for MdnsBonjourPublisher {
    fn drop(&mut self) {
        self.stop();
    }
}
