use base64::{engine::general_purpose::STANDARD, Engine as _};
use rand::{rngs::OsRng, RngCore};

use crate::{
    open_xchacha20_poly1305, seal_xchacha20_poly1305, CryptoError, XCHACHA20_POLY1305_NONCE_LEN,
};

#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SecureEnvelope {
    pub version: u8,
    pub device_id: String,
    pub key_id: String,
    pub counter: u64,
    pub nonce: String,
    pub ciphertext: String,
}

pub fn seal_envelope(
    key: &[u8],
    device_id: &str,
    key_id: &str,
    counter: u64,
    aad: &[u8],
    plaintext: &[u8],
) -> Result<SecureEnvelope, CryptoError> {
    let mut nonce = [0u8; XCHACHA20_POLY1305_NONCE_LEN];
    OsRng.fill_bytes(&mut nonce);
    let ciphertext = seal_xchacha20_poly1305(key, &nonce, aad, plaintext)?;
    Ok(SecureEnvelope {
        version: crate::PROTOCOL_VERSION,
        device_id: device_id.to_owned(),
        key_id: key_id.to_owned(),
        counter,
        nonce: STANDARD.encode(nonce),
        ciphertext: STANDARD.encode(ciphertext),
    })
}

pub fn open_envelope(
    key: &[u8],
    envelope: &SecureEnvelope,
    aad: &[u8],
) -> Result<Vec<u8>, CryptoError> {
    if envelope.version != crate::PROTOCOL_VERSION {
        return Err(CryptoError::UnsupportedVersion {
            expected: crate::PROTOCOL_VERSION,
            actual: envelope.version,
        });
    }
    let nonce = STANDARD
        .decode(&envelope.nonce)
        .map_err(|_| CryptoError::InvalidBase64("nonce"))?;
    if nonce.len() != XCHACHA20_POLY1305_NONCE_LEN {
        return Err(CryptoError::InvalidLength {
            field: "nonce",
            expected: XCHACHA20_POLY1305_NONCE_LEN,
            actual: nonce.len(),
        });
    }
    let ciphertext = STANDARD
        .decode(&envelope.ciphertext)
        .map_err(|_| CryptoError::InvalidBase64("ciphertext"))?;
    open_xchacha20_poly1305(key, &nonce, aad, &ciphertext)
}
