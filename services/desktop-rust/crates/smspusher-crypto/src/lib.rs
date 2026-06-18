pub mod aead;
mod boringssl;
pub mod envelope;
pub mod error;
pub mod hkdf;
#[cfg(feature = "android-jni")]
pub mod jni;
pub mod pake;
pub mod transcript;

pub use aead::{open_xchacha20_poly1305, seal_xchacha20_poly1305};
pub use envelope::{open_envelope, seal_envelope, SecureEnvelope};
pub use error::CryptoError;
pub use hkdf::{derive_device_key, derive_pairing_key};
pub use pake::{PakeExchange, PakeKey, PakeRole};
pub use transcript::{PairingTranscript, SecureAad};

pub const PROTOCOL_VERSION: u8 = 2;
pub const PROTOCOL_NAME: &str = "lan-secure-v2";
pub const PAKE_NAME: &str = "boringssl-spake2-v1";
pub const DEVICE_SECRET_LEN: usize = 32;
pub const XCHACHA20_POLY1305_NONCE_LEN: usize = 24;

#[cfg(test)]
mod tests {
    use super::*;

    fn transcript() -> PairingTranscript {
        PairingTranscript::new(
            "session-1",
            "desktop-1",
            "Test Desktop",
            "android-1",
            "Test Android Device",
            "http://192.0.2.10:55515",
            "2026-06-15T08:00:30Z",
        )
    }

    #[test]
    fn spake2_parties_derive_same_key_with_same_code() {
        let transcript = transcript();
        let mut client = PakeExchange::new(PakeRole::Client, &transcript).unwrap();
        let mut server = PakeExchange::new(PakeRole::Server, &transcript).unwrap();

        let client_msg = client.generate_message("123456").unwrap();
        let server_msg = server.generate_message("123456").unwrap();
        let client_key = client.process_message(&server_msg).unwrap();
        let server_key = server.process_message(&client_msg).unwrap();

        assert_eq!(client_key.as_bytes(), server_key.as_bytes());
    }

    #[test]
    fn spake2_confirmation_fails_with_wrong_code() {
        let transcript = transcript();
        let mut client = PakeExchange::new(PakeRole::Client, &transcript).unwrap();
        let mut server = PakeExchange::new(PakeRole::Server, &transcript).unwrap();

        let client_msg = client.generate_message("123456").unwrap();
        let server_msg = server.generate_message("654321").unwrap();
        let client_key = client.process_message(&server_msg).unwrap();
        let server_key = server.process_message(&client_msg).unwrap();

        assert_ne!(
            client_key.confirmation_mac("client", b"finish").unwrap(),
            server_key.confirmation_mac("client", b"finish").unwrap()
        );
    }

    #[test]
    fn envelope_open_rejects_tampered_aad() {
        let key = [7u8; 32];
        let aad = SecureAad::new("POST", "/secure/messages", "dev_1", "key_1", 1);
        let envelope = seal_envelope(
            &key,
            "dev_1",
            "key_1",
            1,
            aad.as_bytes(),
            br#"{"messageId":"msg_1"}"#,
        )
        .unwrap();

        let tampered_aad = SecureAad::new("POST", "/secure/auth/check", "dev_1", "key_1", 1);
        let opened = open_envelope(&key, &envelope, tampered_aad.as_bytes());

        assert_eq!(opened.unwrap_err(), CryptoError::DecryptFailed);
    }

    #[test]
    fn envelope_open_rejects_unsupported_version() {
        let key = [7u8; 32];
        let aad = SecureAad::new("POST", "/secure/messages", "dev_1", "key_1", 1);
        let mut envelope = seal_envelope(
            &key,
            "dev_1",
            "key_1",
            1,
            aad.as_bytes(),
            br#"{"messageId":"msg_1"}"#,
        )
        .unwrap();
        envelope.version = 1;

        let opened = open_envelope(&key, &envelope, aad.as_bytes());

        assert_eq!(
            opened.unwrap_err(),
            CryptoError::UnsupportedVersion {
                expected: PROTOCOL_VERSION,
                actual: 1
            }
        );
    }
}
