use std::ffi::c_int;

pub const SPAKE2_MAX_MSG_SIZE: usize = 32;
pub const SPAKE2_MAX_KEY_SIZE: usize = 64;
pub const EVP_AEAD_DEFAULT_TAG_LENGTH: usize = 0;

#[repr(C)]
pub struct SPAKE2_CTX {
    _private: [u8; 0],
}

#[repr(C)]
pub struct EVP_AEAD {
    _private: [u8; 0],
}

#[repr(C)]
pub struct EVP_AEAD_CTX {
    _private: [u8; 0],
}

#[repr(C)]
pub struct EVP_MD {
    _private: [u8; 0],
}

#[repr(C)]
#[derive(Clone, Copy)]
pub enum Spake2Role {
    Alice = 0,
    Bob = 1,
}

extern "C" {
    pub fn SPAKE2_CTX_new(
        my_role: Spake2Role,
        my_name: *const u8,
        my_name_len: usize,
        their_name: *const u8,
        their_name_len: usize,
    ) -> *mut SPAKE2_CTX;
    pub fn SPAKE2_CTX_free(ctx: *mut SPAKE2_CTX);
    pub fn SPAKE2_generate_msg(
        ctx: *mut SPAKE2_CTX,
        out: *mut u8,
        out_len: *mut usize,
        max_out_len: usize,
        password: *const u8,
        password_len: usize,
    ) -> c_int;
    pub fn SPAKE2_process_msg(
        ctx: *mut SPAKE2_CTX,
        out_key: *mut u8,
        out_key_len: *mut usize,
        max_out_key_len: usize,
        their_msg: *const u8,
        their_msg_len: usize,
    ) -> c_int;

    pub fn EVP_aead_xchacha20_poly1305() -> *const EVP_AEAD;
    pub fn EVP_AEAD_key_length(aead: *const EVP_AEAD) -> usize;
    pub fn EVP_AEAD_nonce_length(aead: *const EVP_AEAD) -> usize;
    pub fn EVP_AEAD_max_overhead(aead: *const EVP_AEAD) -> usize;
    pub fn EVP_AEAD_CTX_new(
        aead: *const EVP_AEAD,
        key: *const u8,
        key_len: usize,
        tag_len: usize,
    ) -> *mut EVP_AEAD_CTX;
    pub fn EVP_AEAD_CTX_free(ctx: *mut EVP_AEAD_CTX);
    pub fn EVP_AEAD_CTX_seal(
        ctx: *const EVP_AEAD_CTX,
        out: *mut u8,
        out_len: *mut usize,
        max_out_len: usize,
        nonce: *const u8,
        nonce_len: usize,
        input: *const u8,
        input_len: usize,
        ad: *const u8,
        ad_len: usize,
    ) -> c_int;
    pub fn EVP_AEAD_CTX_open(
        ctx: *const EVP_AEAD_CTX,
        out: *mut u8,
        out_len: *mut usize,
        max_out_len: usize,
        nonce: *const u8,
        nonce_len: usize,
        input: *const u8,
        input_len: usize,
        ad: *const u8,
        ad_len: usize,
    ) -> c_int;

    pub fn HKDF(
        out_key: *mut u8,
        out_len: usize,
        digest: *const EVP_MD,
        secret: *const u8,
        secret_len: usize,
        salt: *const u8,
        salt_len: usize,
        info: *const u8,
        info_len: usize,
    ) -> c_int;
    pub fn EVP_sha256() -> *const EVP_MD;
}

pub struct Spake2Context(*mut SPAKE2_CTX);

unsafe impl Send for Spake2Context {}

impl Spake2Context {
    pub fn new(
        role: Spake2Role,
        my_name: &[u8],
        their_name: &[u8],
    ) -> Result<Self, crate::CryptoError> {
        let ctx = unsafe {
            SPAKE2_CTX_new(
                role,
                my_name.as_ptr(),
                my_name.len(),
                their_name.as_ptr(),
                their_name.len(),
            )
        };
        if ctx.is_null() {
            Err(crate::CryptoError::BoringSsl("SPAKE2_CTX_new"))
        } else {
            Ok(Self(ctx))
        }
    }

    pub fn as_mut_ptr(&mut self) -> *mut SPAKE2_CTX {
        self.0
    }
}

impl Drop for Spake2Context {
    fn drop(&mut self) {
        unsafe { SPAKE2_CTX_free(self.0) }
    }
}

pub struct AeadContext(*mut EVP_AEAD_CTX);

impl AeadContext {
    pub fn xchacha20_poly1305(key: &[u8]) -> Result<Self, crate::CryptoError> {
        let aead = unsafe { EVP_aead_xchacha20_poly1305() };
        if aead.is_null() {
            return Err(crate::CryptoError::BoringSsl("EVP_aead_xchacha20_poly1305"));
        }
        let expected = unsafe { EVP_AEAD_key_length(aead) };
        if key.len() != expected {
            return Err(crate::CryptoError::InvalidLength {
                field: "key",
                expected,
                actual: key.len(),
            });
        }
        let ctx =
            unsafe { EVP_AEAD_CTX_new(aead, key.as_ptr(), key.len(), EVP_AEAD_DEFAULT_TAG_LENGTH) };
        if ctx.is_null() {
            Err(crate::CryptoError::BoringSsl("EVP_AEAD_CTX_new"))
        } else {
            Ok(Self(ctx))
        }
    }

    pub fn as_ptr(&self) -> *const EVP_AEAD_CTX {
        self.0
    }
}

impl Drop for AeadContext {
    fn drop(&mut self) {
        unsafe { EVP_AEAD_CTX_free(self.0) }
    }
}

pub fn xchacha20_poly1305_aead() -> Result<*const EVP_AEAD, crate::CryptoError> {
    let aead = unsafe { EVP_aead_xchacha20_poly1305() };
    if aead.is_null() {
        Err(crate::CryptoError::BoringSsl("EVP_aead_xchacha20_poly1305"))
    } else {
        Ok(aead)
    }
}
