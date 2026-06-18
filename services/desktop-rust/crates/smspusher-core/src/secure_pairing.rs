#[derive(Clone)]
pub struct ActiveSecurePairing {
    pub session_id: String,
    pub expires_at: chrono::DateTime<chrono::Utc>,
    pub failed_attempts: u8,
    pub server_message: Option<Vec<u8>>,
    pub pairing_key: Option<Vec<u8>>,
    pub key_id: Option<String>,
    pub client_instance_id: Option<String>,
    pub device_name: Option<String>,
}

impl ActiveSecurePairing {
    pub const MAX_FAILED_ATTEMPTS: u8 = 3;

    pub fn new(session_id: String, expires_at: chrono::DateTime<chrono::Utc>) -> Self {
        Self {
            session_id,
            expires_at,
            failed_attempts: 0,
            server_message: None,
            pairing_key: None,
            key_id: None,
            client_instance_id: None,
            device_name: None,
        }
    }

    pub fn is_valid(&self, now: chrono::DateTime<chrono::Utc>) -> bool {
        now < self.expires_at && self.failed_attempts < Self::MAX_FAILED_ATTEMPTS
    }

    pub fn record_failure(&mut self) {
        self.failed_attempts = self.failed_attempts.saturating_add(1);
    }
}

#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PairV2StartRequest {
    pub protocol: String,
    pub pairing_session_id: String,
    pub client_instance_id: String,
    pub device_name: String,
    pub client_pake_message: String,
}

#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PairV2StartResponse {
    pub protocol: String,
    pub pairing_session_id: String,
    pub server_pake_message: String,
    pub server_confirm: String,
    pub key_id: String,
    pub expires_at: chrono::DateTime<chrono::Utc>,
}

#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PairV2FinishRequest {
    pub protocol: String,
    pub pairing_session_id: String,
    pub client_confirm: String,
    pub encrypted_pair_request: smspusher_crypto::SecureEnvelope,
}

#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PairV2FinishResponse {
    pub encrypted_pair_response: smspusher_crypto::SecureEnvelope,
}

#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PairV2PlainRequest {
    pub device_name: String,
    pub client_instance_id: String,
    pub client_version: u32,
}

#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PairV2PlainResponse {
    pub device_id: String,
    pub key_id: String,
    pub device_secret: String,
    pub desktop_device_name: String,
    pub paired_at: chrono::DateTime<chrono::Utc>,
}
