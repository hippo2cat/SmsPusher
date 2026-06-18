use std::{
    collections::HashMap,
    ptr::null_mut,
    sync::{Mutex, OnceLock},
};

use base64::{engine::general_purpose::STANDARD, Engine as _};
use jni::{
    objects::{JClass, JString},
    sys::jstring,
    JNIEnv,
};
use rand::{rngs::OsRng, RngCore};
use serde::{Deserialize, Serialize};

use crate::{
    open_envelope, seal_envelope, PairingTranscript, PakeExchange, PakeRole, SecureEnvelope,
};

static PAKE_STATES: OnceLock<Mutex<HashMap<String, PakeExchange>>> = OnceLock::new();

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct TranscriptInput {
    pairing_session_id: String,
    desktop_service_instance_id: String,
    desktop_device_name: String,
    android_client_instance_id: String,
    android_device_name: String,
    desktop_base_url: String,
    pairing_expires_at: String,
}

impl TranscriptInput {
    fn into_transcript(self) -> PairingTranscript {
        PairingTranscript::new(
            &self.pairing_session_id,
            &self.desktop_service_instance_id,
            &self.desktop_device_name,
            &self.android_client_instance_id,
            &self.android_device_name,
            &self.desktop_base_url,
            &self.pairing_expires_at,
        )
    }
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct PakeStateInput {
    state_id: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct StartPairingOutput {
    state_json: String,
    message_base64: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct FinishPairingOutput {
    key_base64: String,
    client_confirm_base64: String,
    server_confirm_base64: String,
}

#[no_mangle]
pub extern "system" fn Java_com_hippo2cat_smspusher_crypto_SmsPusherCrypto_startPairing(
    mut env: JNIEnv,
    _class: JClass,
    role: JString,
    transcript_json: JString,
    pairing_code: JString,
) -> jstring {
    let result = (|| {
        let role = jstring_to_string(&mut env, &role)?;
        let transcript_json = jstring_to_string(&mut env, &transcript_json)?;
        let pairing_code = jstring_to_string(&mut env, &pairing_code)?;
        let pake_role = match role.as_str() {
            "client" => PakeRole::Client,
            "server" => PakeRole::Server,
            _ => return Ok(error_json("invalid_role")),
        };
        let transcript: TranscriptInput =
            serde_json::from_str(&transcript_json).map_err(|_| "invalid_transcript")?;
        let mut exchange = PakeExchange::new(pake_role, &transcript.into_transcript())
            .map_err(|_| "pake_failed")?;
        let message = exchange
            .generate_message(&pairing_code)
            .map_err(|_| "pake_failed")?;
        let state_id = random_state_id();
        states()
            .lock()
            .map_err(|_| "state_lock_failed")?
            .insert(state_id.clone(), exchange);
        Ok(serde_json::to_string(&StartPairingOutput {
            state_json: serde_json::to_string(&PakeStateInput { state_id })
                .map_err(|_| "json_failed")?,
            message_base64: STANDARD.encode(message),
        })
        .map_err(|_| "json_failed")?)
    })();
    result_to_jstring(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_com_hippo2cat_smspusher_crypto_SmsPusherCrypto_finishPairing(
    mut env: JNIEnv,
    _class: JClass,
    state_json: JString,
    peer_message_base64: JString,
) -> jstring {
    let result = (|| {
        let state_json = jstring_to_string(&mut env, &state_json)?;
        let peer_message_base64 = jstring_to_string(&mut env, &peer_message_base64)?;
        let state: PakeStateInput =
            serde_json::from_str(&state_json).map_err(|_| "invalid_state")?;
        let peer_message = STANDARD
            .decode(peer_message_base64)
            .map_err(|_| "invalid_base64")?;
        let mut exchange = states()
            .lock()
            .map_err(|_| "state_lock_failed")?
            .remove(&state.state_id)
            .ok_or("invalid_state")?;
        let key = exchange
            .process_message(&peer_message)
            .map_err(|_| "pake_failed")?;
        Ok(serde_json::to_string(&FinishPairingOutput {
            key_base64: STANDARD.encode(key.as_bytes()),
            client_confirm_base64: STANDARD.encode(
                key.confirmation_mac("client", b"finish")
                    .map_err(|_| "pake_failed")?,
            ),
            server_confirm_base64: STANDARD.encode(
                key.confirmation_mac("server", b"start")
                    .map_err(|_| "pake_failed")?,
            ),
        })
        .map_err(|_| "json_failed")?)
    })();
    result_to_jstring(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_com_hippo2cat_smspusher_crypto_SmsPusherCrypto_sealEnvelope(
    mut env: JNIEnv,
    _class: JClass,
    key_base64: JString,
    device_id: JString,
    key_id: JString,
    counter: i64,
    aad: JString,
    plaintext_json: JString,
) -> jstring {
    let result = (|| {
        let key_base64 = jstring_to_string(&mut env, &key_base64)?;
        let device_id = jstring_to_string(&mut env, &device_id)?;
        let key_id = jstring_to_string(&mut env, &key_id)?;
        let aad = jstring_to_string(&mut env, &aad)?;
        let plaintext_json = jstring_to_string(&mut env, &plaintext_json)?;
        let key = envelope_key(&key_base64, &key_id, &aad)?;
        let envelope = seal_envelope(
            &key,
            &device_id,
            &key_id,
            counter.max(0) as u64,
            aad.as_bytes(),
            plaintext_json.as_bytes(),
        )
        .map_err(|_| "encrypt_failed")?;
        serde_json::to_string(&envelope).map_err(|_| "json_failed")
    })();
    result_to_jstring(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_com_hippo2cat_smspusher_crypto_SmsPusherCrypto_openEnvelope(
    mut env: JNIEnv,
    _class: JClass,
    key_base64: JString,
    envelope_json: JString,
    aad: JString,
) -> jstring {
    let result = (|| {
        let key_base64 = jstring_to_string(&mut env, &key_base64)?;
        let envelope_json = jstring_to_string(&mut env, &envelope_json)?;
        let aad = jstring_to_string(&mut env, &aad)?;
        let envelope: SecureEnvelope =
            serde_json::from_str(&envelope_json).map_err(|_| "invalid_envelope")?;
        let key = envelope_key(&key_base64, &envelope.key_id, &aad)?;
        let plain = open_envelope(&key, &envelope, aad.as_bytes()).map_err(|_| "decrypt_failed")?;
        String::from_utf8(plain).map_err(|_| "invalid_plaintext")
    })();
    result_to_jstring(&mut env, result)
}

fn states() -> &'static Mutex<HashMap<String, PakeExchange>> {
    PAKE_STATES.get_or_init(|| Mutex::new(HashMap::new()))
}

fn envelope_key(key_base64: &str, key_id: &str, aad: &str) -> Result<Vec<u8>, &'static str> {
    let decoded = STANDARD.decode(key_base64).map_err(|_| "invalid_base64")?;
    if aad.contains("\n/secure/messages\n") {
        crate::derive_device_key(&decoded, key_id, "message")
            .map(|key| key.to_vec())
            .map_err(|_| "invalid_key")
    } else if aad.contains("\n/secure/auth/check\n") {
        crate::derive_device_key(&decoded, key_id, "auth-check")
            .map(|key| key.to_vec())
            .map_err(|_| "invalid_key")
    } else {
        Ok(decoded)
    }
}

fn jstring_to_string(env: &mut JNIEnv, value: &JString) -> Result<String, &'static str> {
    env.get_string(value)
        .map(|value| value.into())
        .map_err(|_| "invalid_jstring")
}

fn result_to_jstring(env: &mut JNIEnv, result: Result<String, &'static str>) -> jstring {
    let body = match result {
        Ok(body) => body,
        Err(error) => error_json(error),
    };
    env.new_string(body)
        .map(|value| value.into_raw())
        .unwrap_or_else(|_| null_mut())
}

fn error_json(error: &str) -> String {
    format!(r#"{{"error":"{error}"}}"#)
}

fn random_state_id() -> String {
    let mut bytes = [0u8; 16];
    OsRng.fill_bytes(&mut bytes);
    hex_encode(&bytes)
}

fn hex_encode(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        out.push(HEX[(byte >> 4) as usize] as char);
        out.push(HEX[(byte & 0x0f) as usize] as char);
    }
    out
}
