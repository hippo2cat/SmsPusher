use crate::{boringssl, CryptoError};

pub fn seal_xchacha20_poly1305(
    key: &[u8],
    nonce: &[u8],
    aad: &[u8],
    plaintext: &[u8],
) -> Result<Vec<u8>, CryptoError> {
    let aead = boringssl::xchacha20_poly1305_aead()?;
    let expected_nonce = unsafe { boringssl::EVP_AEAD_nonce_length(aead) };
    if nonce.len() != expected_nonce {
        return Err(CryptoError::InvalidLength {
            field: "nonce",
            expected: expected_nonce,
            actual: nonce.len(),
        });
    }
    let ctx = boringssl::AeadContext::xchacha20_poly1305(key)?;
    let max_out_len = plaintext.len() + unsafe { boringssl::EVP_AEAD_max_overhead(aead) };
    let mut out = vec![0u8; max_out_len];
    let mut out_len = 0usize;
    let ok = unsafe {
        boringssl::EVP_AEAD_CTX_seal(
            ctx.as_ptr(),
            out.as_mut_ptr(),
            &mut out_len,
            out.len(),
            nonce.as_ptr(),
            nonce.len(),
            plaintext.as_ptr(),
            plaintext.len(),
            aad.as_ptr(),
            aad.len(),
        )
    };
    if ok == 1 {
        out.truncate(out_len);
        Ok(out)
    } else {
        Err(CryptoError::BoringSsl("EVP_AEAD_CTX_seal"))
    }
}

pub fn open_xchacha20_poly1305(
    key: &[u8],
    nonce: &[u8],
    aad: &[u8],
    ciphertext: &[u8],
) -> Result<Vec<u8>, CryptoError> {
    let aead = boringssl::xchacha20_poly1305_aead()?;
    let expected_nonce = unsafe { boringssl::EVP_AEAD_nonce_length(aead) };
    if nonce.len() != expected_nonce {
        return Err(CryptoError::InvalidLength {
            field: "nonce",
            expected: expected_nonce,
            actual: nonce.len(),
        });
    }
    let ctx = boringssl::AeadContext::xchacha20_poly1305(key)?;
    let mut out = vec![0u8; ciphertext.len()];
    let mut out_len = 0usize;
    let ok = unsafe {
        boringssl::EVP_AEAD_CTX_open(
            ctx.as_ptr(),
            out.as_mut_ptr(),
            &mut out_len,
            out.len(),
            nonce.as_ptr(),
            nonce.len(),
            ciphertext.as_ptr(),
            ciphertext.len(),
            aad.as_ptr(),
            aad.len(),
        )
    };
    if ok == 1 {
        out.truncate(out_len);
        Ok(out)
    } else {
        Err(CryptoError::DecryptFailed)
    }
}
