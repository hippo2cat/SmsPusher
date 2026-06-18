use chrono::{DateTime, Utc};
use serde::Serialize;
use smspusher_core::PairingCode;

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DeviceSnapshot {
    pub device_id: String,
    pub device_name: String,
    pub revoked: bool,
    pub secure_transport_version: Option<u8>,
    pub key_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MessageSnapshot {
    pub message_id: String,
    pub sender: String,
    pub body: String,
    pub received_at: DateTime<Utc>,
    pub subscription_id: i32,
    pub device_id: String,
    pub verification_code: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TransportSnapshot {
    pub lan_port: Option<u16>,
    pub mdns_service_type: String,
    pub status: String,
    pub secure_protocol: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StatusSnapshot {
    pub service_name: String,
    pub preferred_port: u16,
    pub pairing_code: PairingCodeSnapshot,
    pub devices: Vec<DeviceSnapshot>,
    pub latest_messages: Vec<MessageSnapshot>,
    pub transport: TransportSnapshot,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PairingCodeSnapshot {
    pub value: String,
    pub session_id: String,
    pub expires_at: DateTime<Utc>,
}

impl From<&PairingCode> for PairingCodeSnapshot {
    fn from(value: &PairingCode) -> Self {
        Self {
            value: value.value.clone(),
            session_id: value.session_id.clone(),
            expires_at: value.expires_at,
        }
    }
}
