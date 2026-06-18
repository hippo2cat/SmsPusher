#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SecureAuthCheckPlainRequest {
    pub reason: String,
}

#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SecureMessagePlainRequest {
    pub message_id: String,
    pub sender: String,
    pub body: String,
    pub received_at: chrono::DateTime<chrono::Utc>,
    pub subscription_id: i32,
}
