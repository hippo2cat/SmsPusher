#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum CryptoError {
    #[error("boringssl operation failed: {0}")]
    BoringSsl(&'static str),
    #[error("invalid length for {field}: expected {expected}, actual {actual}")]
    InvalidLength {
        field: &'static str,
        expected: usize,
        actual: usize,
    },
    #[error("unsupported protocol version: expected {expected}, actual {actual}")]
    UnsupportedVersion { expected: u8, actual: u8 },
    #[error("invalid base64 field: {0}")]
    InvalidBase64(&'static str),
    #[error("decryption failed")]
    DecryptFailed,
    #[error("pairing confirmation failed")]
    PairingConfirmationFailed,
}
