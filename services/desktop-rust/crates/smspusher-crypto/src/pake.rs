use crate::{
    boringssl::{self, Spake2Role},
    derive_pairing_key, CryptoError, PairingTranscript,
};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PakeRole {
    Client,
    Server,
}

pub struct PakeExchange {
    ctx: boringssl::Spake2Context,
    transcript: PairingTranscript,
}

#[derive(Clone, zeroize::Zeroize, zeroize::ZeroizeOnDrop)]
pub struct PakeKey {
    bytes: [u8; 32],
}

impl PakeKey {
    pub fn as_bytes(&self) -> &[u8; 32] {
        &self.bytes
    }

    pub fn confirmation_mac(&self, role: &str, extra: &[u8]) -> Result<Vec<u8>, CryptoError> {
        crate::hkdf::hkdf_sha256(&self.bytes, role.as_bytes(), extra, 32)
    }
}

impl PakeExchange {
    pub fn new(role: PakeRole, transcript: &PairingTranscript) -> Result<Self, CryptoError> {
        let (my_name, their_name, spake_role) = match role {
            PakeRole::Client => (
                transcript.client_name(),
                transcript.server_name(),
                Spake2Role::Alice,
            ),
            PakeRole::Server => (
                transcript.server_name(),
                transcript.client_name(),
                Spake2Role::Bob,
            ),
        };
        Ok(Self {
            ctx: boringssl::Spake2Context::new(spake_role, &my_name, &their_name)?,
            transcript: transcript.clone(),
        })
    }

    pub fn generate_message(&mut self, pairing_code: &str) -> Result<Vec<u8>, CryptoError> {
        let mut out = vec![0u8; boringssl::SPAKE2_MAX_MSG_SIZE];
        let mut out_len = 0usize;
        let password = pairing_code.as_bytes();
        let ok = unsafe {
            boringssl::SPAKE2_generate_msg(
                self.ctx.as_mut_ptr(),
                out.as_mut_ptr(),
                &mut out_len,
                out.len(),
                password.as_ptr(),
                password.len(),
            )
        };
        if ok == 1 {
            out.truncate(out_len);
            Ok(out)
        } else {
            Err(CryptoError::BoringSsl("SPAKE2_generate_msg"))
        }
    }

    pub fn process_message(&mut self, peer_message: &[u8]) -> Result<PakeKey, CryptoError> {
        let mut raw = vec![0u8; boringssl::SPAKE2_MAX_KEY_SIZE];
        let mut raw_len = 0usize;
        let ok = unsafe {
            boringssl::SPAKE2_process_msg(
                self.ctx.as_mut_ptr(),
                raw.as_mut_ptr(),
                &mut raw_len,
                raw.len(),
                peer_message.as_ptr(),
                peer_message.len(),
            )
        };
        if ok != 1 {
            return Err(CryptoError::BoringSsl("SPAKE2_process_msg"));
        }
        raw.truncate(raw_len);
        Ok(PakeKey {
            bytes: derive_pairing_key(&raw, self.transcript.as_bytes(), "spake2 shared key")?,
        })
    }
}
