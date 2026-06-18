use crate::{boringssl, CryptoError};

pub fn hkdf_sha256(
    secret: &[u8],
    salt: &[u8],
    info: &[u8],
    len: usize,
) -> Result<Vec<u8>, CryptoError> {
    let mut out = vec![0u8; len];
    let ok = unsafe {
        boringssl::HKDF(
            out.as_mut_ptr(),
            out.len(),
            boringssl::EVP_sha256(),
            secret.as_ptr(),
            secret.len(),
            salt.as_ptr(),
            salt.len(),
            info.as_ptr(),
            info.len(),
        )
    };
    if ok == 1 {
        Ok(out)
    } else {
        Err(CryptoError::BoringSsl("HKDF"))
    }
}

pub fn derive_pairing_key(
    spake_key: &[u8],
    transcript: &[u8],
    purpose: &str,
) -> Result<[u8; 32], CryptoError> {
    let info = ["SmsPusher", crate::PROTOCOL_NAME, purpose].join("\n");
    let bytes = hkdf_sha256(spake_key, transcript, info.as_bytes(), 32)?;
    let mut out = [0u8; 32];
    out.copy_from_slice(&bytes);
    Ok(out)
}

pub fn derive_device_key(
    device_secret: &[u8],
    key_id: &str,
    purpose: &str,
) -> Result<[u8; 32], CryptoError> {
    let info = ["SmsPusher", crate::PROTOCOL_NAME, purpose].join("\n");
    let bytes = hkdf_sha256(device_secret, key_id.as_bytes(), info.as_bytes(), 32)?;
    let mut out = [0u8; 32];
    out.copy_from_slice(&bytes);
    Ok(out)
}
