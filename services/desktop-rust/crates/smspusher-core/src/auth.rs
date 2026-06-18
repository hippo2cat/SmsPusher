use crate::{
    device_store::DeviceStore,
    models::{PairRequest, PairResponse, RefreshRequest, RefreshResponse, StoredDevice},
    token::{PairingCode, TokenGenerator},
};
use chrono::{DateTime, Duration, Utc};
use sha2::{Digest, Sha256};
use thiserror::Error;

const ACCESS_TOKEN_LIFETIME_SECONDS: i64 = 86_400;
const REFRESH_TOKEN_LIFETIME_SECONDS: i64 = 7_776_000;

#[derive(Debug, Clone, PartialEq, Eq, Error)]
pub enum AuthError {
    #[error("pairing_not_started")]
    PairingNotStarted,
    #[error("invalid_pairing_code")]
    InvalidPairingCode,
    #[error("pairing_code_expired")]
    PairingCodeExpired,
    #[error("invalid_refresh_token")]
    InvalidRefreshToken,
    #[error("refresh_token_expired")]
    RefreshTokenExpired,
    #[error("device_revoked")]
    DeviceRevoked,
    #[error("invalid_access_token")]
    InvalidAccessToken,
    #[error("access_token_expired")]
    AccessTokenExpired,
    #[error("invalid_pairing_session")]
    InvalidPairingSession,
    #[error("too_many_pairing_attempts")]
    TooManyPairingAttempts,
    #[error("pairing_confirmation_failed")]
    PairingConfirmationFailed,
    #[error("invalid_secure_envelope")]
    InvalidSecureEnvelope,
    #[error("unknown_device")]
    UnknownDevice,
    #[error("invalid_key")]
    InvalidKey,
    #[error("replay_detected")]
    ReplayDetected,
    #[error("decrypt_failed")]
    DecryptFailed,
    #[error("secure_pairing_required")]
    SecurePairingRequired,
}

pub struct AuthService<S, N>
where
    S: DeviceStore,
    N: Fn() -> DateTime<Utc>,
{
    store: S,
    token_generator: TokenGenerator,
    now: N,
    active_pairing_code: Option<PairingCode>,
}

impl<S, N> AuthService<S, N>
where
    S: DeviceStore,
    N: Fn() -> DateTime<Utc>,
{
    pub fn new(store: S, token_generator: TokenGenerator, now: N) -> Self {
        Self {
            store,
            token_generator,
            now,
            active_pairing_code: None,
        }
    }

    pub fn device_store(&self) -> &S {
        &self.store
    }

    pub fn device_store_mut(&mut self) -> &mut S {
        &mut self.store
    }

    pub fn now(&self) -> DateTime<Utc> {
        (self.now)()
    }

    pub fn start_pairing(&mut self) -> PairingCode {
        let code = self.token_generator.make_pairing_code((self.now)());
        self.active_pairing_code = Some(code.clone());
        code
    }

    pub fn refresh_pairing_code_if_needed(&mut self) -> PairingCode {
        if let Some(code) = &self.active_pairing_code {
            if code.is_valid((self.now)()) {
                return code.clone();
            }
        }
        self.start_pairing()
    }

    pub fn pair(&mut self, request: PairRequest) -> Result<PairResponse, AuthError> {
        let code = self
            .active_pairing_code
            .as_ref()
            .ok_or(AuthError::PairingNotStarted)?;
        if code.value != request.pairing_code {
            return Err(AuthError::InvalidPairingCode);
        }
        if !code.is_valid((self.now)()) {
            return Err(AuthError::PairingCodeExpired);
        }

        let client_instance_id = request
            .client_instance_id
            .as_deref()
            .map(str::trim)
            .filter(|value| !value.is_empty())
            .map(ToOwned::to_owned);
        let existing = client_instance_id
            .as_deref()
            .and_then(|id| self.store.device_by_client_instance_id(id))
            .or_else(|| self.unclaimed_legacy_device(&request.device_name));

        let device_id = existing
            .as_ref()
            .map(|device| device.device_id.clone())
            .unwrap_or_else(|| {
                format!(
                    "dev_{}",
                    self.token_generator
                        .make_opaque_token()
                        .chars()
                        .take(12)
                        .collect::<String>()
                )
            });
        let access_token = self.token_generator.make_opaque_token();
        let refresh_token = self.token_generator.make_opaque_token();
        let access_token_expires_at =
            (self.now)() + Duration::seconds(ACCESS_TOKEN_LIFETIME_SECONDS);
        let refresh_token_expires_at =
            (self.now)() + Duration::seconds(REFRESH_TOKEN_LIFETIME_SECONDS);
        let device = StoredDevice {
            device_id: device_id.clone(),
            device_name: request.device_name,
            client_instance_id,
            access_token_hash: Self::hash(&access_token),
            access_token_expires_at,
            refresh_token_hash: Self::hash(&refresh_token),
            refresh_token_expires_at,
            revoked: false,
            secure_transport_version: None,
            key_id: None,
            highest_counter: 0,
            paired_at: None,
            last_seen_at: None,
        };

        if existing.is_some() {
            self.store.update(device);
        } else {
            self.store.save(device);
        }
        self.active_pairing_code = None;

        Ok(PairResponse {
            device_id,
            access_token,
            access_token_expires_at,
            refresh_token,
            refresh_token_expires_at,
        })
    }

    pub fn refresh(&mut self, request: RefreshRequest) -> Result<RefreshResponse, AuthError> {
        let mut device = self
            .store
            .device(&request.device_id)
            .ok_or(AuthError::InvalidRefreshToken)?;
        if device.revoked {
            return Err(AuthError::DeviceRevoked);
        }
        if device.refresh_token_hash != Self::hash(&request.refresh_token) {
            return Err(AuthError::InvalidRefreshToken);
        }
        if (self.now)() >= device.refresh_token_expires_at {
            return Err(AuthError::RefreshTokenExpired);
        }

        let access_token = self.token_generator.make_opaque_token();
        let refresh_token = self.token_generator.make_opaque_token();
        let access_token_expires_at =
            (self.now)() + Duration::seconds(ACCESS_TOKEN_LIFETIME_SECONDS);
        let refresh_token_expires_at =
            (self.now)() + Duration::seconds(REFRESH_TOKEN_LIFETIME_SECONDS);
        device.access_token_hash = Self::hash(&access_token);
        device.access_token_expires_at = access_token_expires_at;
        device.refresh_token_hash = Self::hash(&refresh_token);
        device.refresh_token_expires_at = refresh_token_expires_at;
        self.store.update(device);

        Ok(RefreshResponse {
            access_token,
            access_token_expires_at,
            refresh_token,
            refresh_token_expires_at,
        })
    }

    pub fn authenticate(&self, device_id: &str, access_token: &str) -> Result<(), AuthError> {
        let device = self
            .store
            .device(device_id)
            .ok_or(AuthError::InvalidAccessToken)?;
        if device.revoked {
            return Err(AuthError::DeviceRevoked);
        }
        if device.access_token_hash != Self::hash(access_token) {
            return Err(AuthError::InvalidAccessToken);
        }
        if (self.now)() >= device.access_token_expires_at {
            return Err(AuthError::AccessTokenExpired);
        }
        Ok(())
    }

    pub fn revoke(&mut self, device_id: &str) {
        self.store.revoke(device_id);
    }

    pub fn hash(value: &str) -> String {
        let digest = Sha256::digest(value.as_bytes());
        hex::encode(digest)
    }

    fn unclaimed_legacy_device(&self, device_name: &str) -> Option<StoredDevice> {
        let mut candidates = self
            .store
            .all_devices()
            .into_iter()
            .filter(|device| {
                device.client_instance_id.is_none() && device.device_name == device_name
            })
            .collect::<Vec<_>>();
        if candidates.len() == 1 {
            candidates.pop()
        } else {
            None
        }
    }
}
