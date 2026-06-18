use base64::{engine::general_purpose::STANDARD as BASE64_STANDARD, Engine as _};
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, HashMap};

pub const BLE_FRAME_VERSION: u8 = 1;
pub const SMSPUSHER_SERVICE_UUID: &str = "2f89b10a-9f3d-4f2e-9f7a-3d3b7b5f0a11";
pub const SMSPUSHER_MESSAGE_CHARACTERISTIC_UUID: &str = "2f89b10b-9f3d-4f2e-9f7a-3d3b7b5f0a11";
pub const SMSPUSHER_ACK_CHARACTERISTIC_UUID: &str = "2f89b10c-9f3d-4f2e-9f7a-3d3b7b5f0a11";
pub const SMSPUSHER_METADATA_CHARACTERISTIC_UUID: &str = "2f89b10d-9f3d-4f2e-9f7a-3d3b7b5f0a11";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BleFrame {
    pub version: u8,
    pub message_id: String,
    pub chunk_index: u16,
    pub chunk_count: u16,
    pub payload: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum BleTransportState {
    Stopped,
    Scanning,
    Connecting,
    Connected,
    PermissionRequired,
    AdapterUnavailable,
}

impl Default for BleTransportState {
    fn default() -> Self {
        Self::Stopped
    }
}

impl BleTransportState {
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Stopped => "stopped",
            Self::Scanning => "scanning",
            Self::Connecting => "connecting",
            Self::Connected => "connected",
            Self::PermissionRequired => "permission_required",
            Self::AdapterUnavailable => "adapter_unavailable",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BlePeripheral {
    pub id: String,
    pub name: Option<String>,
    pub rssi: Option<i16>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum BleCentralEvent {
    StateChanged(BleTransportState),
    PeripheralDiscovered(BlePeripheral),
    Connected { peripheral_id: String },
    Disconnected { peripheral_id: String },
    MessageReceived { payload: Vec<u8> },
    AckWritten { message_id: String },
}

#[async_trait::async_trait]
pub trait BleCentralAdapter {
    async fn scan(&mut self) -> Result<BleTransportState, BleFrameError>;
    async fn connect(&mut self, peripheral_id: &str) -> Result<BleTransportState, BleFrameError>;
    async fn subscribe_messages(&mut self) -> Result<BleTransportState, BleFrameError>;
    async fn write_ack(&mut self, message_id: &str) -> Result<(), BleFrameError>;
    async fn disconnect(&mut self) -> Result<BleTransportState, BleFrameError>;
    fn state(&self) -> BleTransportState;
    fn drain_events(&mut self) -> Vec<BleCentralEvent>;
}

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum BleFrameError {
    #[error("invalid max payload size")]
    InvalidMaxPayloadSize,
    #[error("empty payload")]
    EmptyPayload,
    #[error("invalid chunk index")]
    InvalidChunkIndex,
    #[error("duplicate chunk")]
    DuplicateChunk,
    #[error("inconsistent frame")]
    InconsistentFrame,
    #[error("serialization error: {0}")]
    Serialization(String),
    #[error("invalid base64 payload: {0}")]
    InvalidBase64(String),
    #[error("adapter error: {0}")]
    Adapter(String),
}

pub fn chunk_payload(
    message_id: impl Into<String>,
    payload: &[u8],
    max_payload_size: usize,
) -> Result<Vec<BleFrame>, BleFrameError> {
    if max_payload_size == 0 {
        return Err(BleFrameError::InvalidMaxPayloadSize);
    }
    if payload.is_empty() {
        return Err(BleFrameError::EmptyPayload);
    }
    let chunk_count = payload.len().div_ceil(max_payload_size);
    if chunk_count > u16::MAX as usize {
        return Err(BleFrameError::InvalidChunkIndex);
    }
    let message_id = message_id.into();
    Ok(payload
        .chunks(max_payload_size)
        .enumerate()
        .map(|(index, chunk)| BleFrame {
            version: BLE_FRAME_VERSION,
            message_id: message_id.clone(),
            chunk_index: index as u16,
            chunk_count: chunk_count as u16,
            payload: chunk.to_vec(),
        })
        .collect())
}

pub fn encode_frame(frame: &BleFrame) -> Result<Vec<u8>, BleFrameError> {
    let serialized = SerializedFrame {
        version: frame.version,
        message_id: frame.message_id.clone(),
        chunk_index: frame.chunk_index,
        chunk_count: frame.chunk_count,
        payload_base64: BASE64_STANDARD.encode(&frame.payload),
    };
    serde_json::to_vec(&serialized).map_err(|error| BleFrameError::Serialization(error.to_string()))
}

pub fn decode_frame(data: &[u8]) -> Result<BleFrame, BleFrameError> {
    let serialized: SerializedFrame = serde_json::from_slice(data)
        .map_err(|error| BleFrameError::Serialization(error.to_string()))?;
    if serialized.version != BLE_FRAME_VERSION {
        return Err(BleFrameError::InconsistentFrame);
    }
    let payload = BASE64_STANDARD
        .decode(serialized.payload_base64)
        .map_err(|error| BleFrameError::InvalidBase64(error.to_string()))?;
    let frame = BleFrame {
        version: serialized.version,
        message_id: serialized.message_id,
        chunk_index: serialized.chunk_index,
        chunk_count: serialized.chunk_count,
        payload,
    };
    validate_frame(&frame)?;
    Ok(frame)
}

pub fn encode_ack(message_id: &str) -> Result<Vec<u8>, BleFrameError> {
    if message_id.is_empty() {
        return Err(BleFrameError::InconsistentFrame);
    }
    serde_json::to_vec(&SerializedAck {
        version: BLE_FRAME_VERSION,
        message_id: message_id.to_owned(),
    })
    .map_err(|error| BleFrameError::Serialization(error.to_string()))
}

pub fn decode_ack(data: &[u8]) -> Result<String, BleFrameError> {
    let ack: SerializedAck = serde_json::from_slice(data)
        .map_err(|error| BleFrameError::Serialization(error.to_string()))?;
    if ack.version != BLE_FRAME_VERSION || ack.message_id.is_empty() {
        return Err(BleFrameError::InconsistentFrame);
    }
    Ok(ack.message_id)
}

#[derive(Default)]
pub struct BleReassembler {
    messages: HashMap<String, PartialMessage>,
}

impl BleReassembler {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn push(&mut self, frame: BleFrame) -> Result<Option<Vec<u8>>, BleFrameError> {
        validate_frame(&frame)?;
        let entry = self
            .messages
            .entry(frame.message_id.clone())
            .or_insert_with(|| PartialMessage {
                chunk_count: frame.chunk_count,
                chunks: BTreeMap::new(),
            });
        if entry.chunk_count != frame.chunk_count {
            return Err(BleFrameError::InconsistentFrame);
        }
        if entry.chunks.contains_key(&frame.chunk_index) {
            return Err(BleFrameError::DuplicateChunk);
        }
        entry.chunks.insert(frame.chunk_index, frame.payload);
        if entry.chunks.len() != entry.chunk_count as usize {
            return Ok(None);
        }

        let mut payload = Vec::new();
        for index in 0..entry.chunk_count {
            let chunk = entry
                .chunks
                .get(&index)
                .ok_or(BleFrameError::InconsistentFrame)?;
            payload.extend_from_slice(chunk);
        }
        self.messages.remove(&frame.message_id);
        Ok(Some(payload))
    }
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SerializedFrame {
    version: u8,
    message_id: String,
    chunk_index: u16,
    chunk_count: u16,
    payload_base64: String,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SerializedAck {
    version: u8,
    message_id: String,
}

#[derive(Default)]
struct PartialMessage {
    chunk_count: u16,
    chunks: BTreeMap<u16, Vec<u8>>,
}

fn validate_frame(frame: &BleFrame) -> Result<(), BleFrameError> {
    if frame.version != BLE_FRAME_VERSION {
        return Err(BleFrameError::InconsistentFrame);
    }
    if frame.chunk_count == 0 || frame.chunk_index >= frame.chunk_count {
        return Err(BleFrameError::InvalidChunkIndex);
    }
    if frame.payload.is_empty() {
        return Err(BleFrameError::EmptyPayload);
    }
    Ok(())
}
