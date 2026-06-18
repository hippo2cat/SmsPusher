use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PairRequest {
    pub pairing_code: String,
    pub device_name: String,
    pub client_version: u32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub client_instance_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PairResponse {
    pub device_id: String,
    pub access_token: String,
    pub access_token_expires_at: DateTime<Utc>,
    pub refresh_token: String,
    pub refresh_token_expires_at: DateTime<Utc>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RefreshRequest {
    pub device_id: String,
    pub refresh_token: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RefreshResponse {
    pub access_token: String,
    pub access_token_expires_at: DateTime<Utc>,
    pub refresh_token: String,
    pub refresh_token_expires_at: DateTime<Utc>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AuthCheckRequest {
    pub device_id: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct IncomingMessage {
    pub message_id: String,
    pub sender: String,
    pub body: String,
    pub received_at: DateTime<Utc>,
    pub subscription_id: i32,
    pub device_id: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StoredDevice {
    pub device_id: String,
    pub device_name: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub client_instance_id: Option<String>,
    pub access_token_hash: String,
    #[serde(with = "swift_date")]
    pub access_token_expires_at: DateTime<Utc>,
    pub refresh_token_hash: String,
    #[serde(with = "swift_date")]
    pub refresh_token_expires_at: DateTime<Utc>,
    pub revoked: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub secure_transport_version: Option<u8>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub key_id: Option<String>,
    #[serde(default)]
    pub highest_counter: u64,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        with = "optional_swift_date"
    )]
    pub paired_at: Option<DateTime<Utc>>,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        with = "optional_swift_date"
    )]
    pub last_seen_at: Option<DateTime<Utc>>,
}

impl StoredDevice {
    pub fn active(&self) -> bool {
        !self.revoked
    }

    pub fn supports_secure_transport(&self) -> bool {
        self.secure_transport_version == Some(2)
            && self
                .key_id
                .as_deref()
                .map(|value| !value.is_empty())
                .unwrap_or(false)
    }
}

mod swift_date {
    use chrono::{DateTime, TimeZone, Utc};
    use serde::{de::Error as _, Deserialize, Deserializer, Serializer};

    const UNIX_SECONDS_AT_APPLE_REFERENCE_DATE: f64 = 978_307_200.0;

    pub fn serialize<S>(value: &DateTime<Utc>, serializer: S) -> Result<S::Ok, S::Error>
    where
        S: Serializer,
    {
        let unix_seconds = value.timestamp_millis() as f64 / 1000.0;
        serializer.serialize_f64(unix_seconds - UNIX_SECONDS_AT_APPLE_REFERENCE_DATE)
    }

    pub fn deserialize<'de, D>(deserializer: D) -> Result<DateTime<Utc>, D::Error>
    where
        D: Deserializer<'de>,
    {
        let value = serde_json::Value::deserialize(deserializer)?;
        if let Some(seconds) = value.as_f64() {
            return date_from_unix_seconds(seconds + UNIX_SECONDS_AT_APPLE_REFERENCE_DATE)
                .ok_or_else(|| D::Error::custom("invalid Swift date value"));
        }
        if let Some(text) = value.as_str() {
            return DateTime::parse_from_rfc3339(text)
                .map(|value| value.with_timezone(&Utc))
                .map_err(D::Error::custom);
        }
        Err(D::Error::custom(
            "expected Swift date number or RFC3339 string",
        ))
    }

    fn date_from_unix_seconds(value: f64) -> Option<DateTime<Utc>> {
        if !value.is_finite() {
            return None;
        }
        let seconds = value.floor() as i64;
        let mut nanos = ((value - seconds as f64) * 1_000_000_000.0).round() as u32;
        let mut adjusted_seconds = seconds;
        if nanos == 1_000_000_000 {
            adjusted_seconds += 1;
            nanos = 0;
        }
        Utc.timestamp_opt(adjusted_seconds, nanos).single()
    }
}

mod optional_swift_date {
    use chrono::{DateTime, TimeZone, Utc};
    use serde::{de::Error as _, Deserialize, Deserializer, Serializer};

    const UNIX_SECONDS_AT_APPLE_REFERENCE_DATE: f64 = 978_307_200.0;

    pub fn serialize<S>(value: &Option<DateTime<Utc>>, serializer: S) -> Result<S::Ok, S::Error>
    where
        S: Serializer,
    {
        match value {
            Some(date) => super::swift_date::serialize(date, serializer),
            None => serializer.serialize_none(),
        }
    }

    pub fn deserialize<'de, D>(deserializer: D) -> Result<Option<DateTime<Utc>>, D::Error>
    where
        D: Deserializer<'de>,
    {
        let value = Option::<serde_json::Value>::deserialize(deserializer)?;
        match value {
            Some(serde_json::Value::Number(number)) => number
                .as_f64()
                .and_then(|seconds| {
                    date_from_unix_seconds(seconds + UNIX_SECONDS_AT_APPLE_REFERENCE_DATE)
                })
                .map(Some)
                .ok_or_else(|| D::Error::custom("invalid Swift date value")),
            Some(serde_json::Value::String(text)) if !text.is_empty() => {
                DateTime::parse_from_rfc3339(&text)
                    .map(|value| Some(value.with_timezone(&Utc)))
                    .map_err(D::Error::custom)
            }
            Some(serde_json::Value::String(_)) | None => Ok(None),
            Some(_) => Err(D::Error::custom(
                "expected optional Swift date number or RFC3339 string",
            )),
        }
    }

    fn date_from_unix_seconds(value: f64) -> Option<DateTime<Utc>> {
        if !value.is_finite() {
            return None;
        }
        let seconds = value.floor() as i64;
        let mut nanos = ((value - seconds as f64) * 1_000_000_000.0).round() as u32;
        let mut adjusted_seconds = seconds;
        if nanos == 1_000_000_000 {
            adjusted_seconds += 1;
            nanos = 0;
        }
        Utc.timestamp_opt(adjusted_seconds, nanos).single()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::TimeZone;

    #[test]
    fn stored_device_serializes_secure_metadata_without_device_secret() {
        let device = StoredDevice {
            device_id: "dev_1".into(),
            device_name: "Pixel 9".into(),
            client_instance_id: Some("android-1".into()),
            access_token_hash: String::new(),
            access_token_expires_at: Utc.with_ymd_and_hms(2026, 6, 15, 8, 0, 0).unwrap(),
            refresh_token_hash: String::new(),
            refresh_token_expires_at: Utc.with_ymd_and_hms(2026, 6, 15, 8, 0, 0).unwrap(),
            revoked: false,
            secure_transport_version: Some(2),
            key_id: Some("key_1".into()),
            highest_counter: 42,
            paired_at: Some(Utc.with_ymd_and_hms(2026, 6, 15, 8, 0, 0).unwrap()),
            last_seen_at: None,
        };

        let json = serde_json::to_string(&device).unwrap();

        assert!(json.contains("\"secureTransportVersion\":2"));
        assert!(json.contains("\"keyId\":\"key_1\""));
        assert!(json.contains("\"highestCounter\":42"));
        assert!(!json.contains("deviceSecret"));
    }
}
