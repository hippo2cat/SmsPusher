use serde::{de::DeserializeOwned, Serialize};

pub fn from_slice<T: DeserializeOwned>(bytes: &[u8]) -> serde_json::Result<T> {
    serde_json::from_slice(bytes)
}

pub fn to_string<T: Serialize>(value: &T) -> serde_json::Result<String> {
    serde_json::to_string(value)
}

pub fn error_body(code: &str) -> String {
    format!(r#"{{"error":"{}"}}"#, code)
}

pub fn status_body(status: &str) -> String {
    format!(r#"{{"status":"{}"}}"#, status)
}
