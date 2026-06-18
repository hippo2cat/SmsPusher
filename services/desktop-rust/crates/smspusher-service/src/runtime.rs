use crate::{
    events::{ServiceEvent, ServiceEventSink},
    snapshot::{
        DeviceSnapshot, MessageSnapshot, PairingCodeSnapshot, StatusSnapshot, TransportSnapshot,
    },
};
use base64::{engine::general_purpose::STANDARD, Engine as _};
use chrono::{DateTime, Utc};
use rand::{rngs::OsRng, RngCore};
use smspusher_core::{
    ActiveSecurePairing, AuthError, AuthService, DeviceStore, IncomingMessage, MessageStore,
    MessageStoreError, PairRequest, PairResponse, PairV2FinishRequest, PairV2FinishResponse,
    PairV2PlainRequest, PairV2PlainResponse, PairV2StartRequest, PairV2StartResponse,
    RefreshRequest, RefreshResponse, SecretStore, SecretStoreError, SecureMessagePlainRequest,
    StoredDevice, TokenGenerator, VerificationCodeExtractor,
};
use smspusher_crypto::{
    open_envelope, seal_envelope, PairingTranscript, PakeExchange, PakeRole, SecureAad,
};
use thiserror::Error;

#[derive(Debug, Clone)]
pub struct DesktopServiceConfig {
    pub service_name: String,
    pub preferred_port: u16,
    pub history_limit: usize,
}

#[derive(Debug, Error)]
pub enum ServiceError {
    #[error("auth error: {0}")]
    Auth(#[from] AuthError),
    #[error("message store error: {0}")]
    MessageStore(#[from] MessageStoreError),
    #[error("secret store error: {0}")]
    SecretStore(#[from] SecretStoreError),
}

pub struct DesktopService<DS, MS, ES, N, SS = smspusher_core::InMemorySecretStore>
where
    DS: DeviceStore,
    MS: MessageStore,
    SS: SecretStore,
    ES: ServiceEventSink,
    N: Fn() -> DateTime<Utc>,
{
    config: DesktopServiceConfig,
    auth: AuthService<DS, N>,
    message_store: MS,
    secret_store: SS,
    events: ES,
    lan_port: Option<u16>,
    pairing_code: smspusher_core::PairingCode,
    secure_pairing: Option<ActiveSecurePairing>,
}

impl<DS, MS, ES, N> DesktopService<DS, MS, ES, N, smspusher_core::InMemorySecretStore>
where
    DS: DeviceStore,
    MS: MessageStore,
    ES: ServiceEventSink,
    N: Fn() -> DateTime<Utc>,
{
    pub fn new_for_tests(
        config: DesktopServiceConfig,
        device_store: DS,
        message_store: MS,
        token_generator: TokenGenerator,
        events: ES,
        now: N,
    ) -> Self {
        Self::new_for_tests_with_secrets(
            config,
            device_store,
            message_store,
            smspusher_core::InMemorySecretStore::default(),
            token_generator,
            events,
            now,
        )
    }
}

impl<DS, MS, ES, N, SS> DesktopService<DS, MS, ES, N, SS>
where
    DS: DeviceStore,
    MS: MessageStore,
    SS: SecretStore,
    ES: ServiceEventSink,
    N: Fn() -> DateTime<Utc>,
{
    pub fn new_for_tests_with_secrets(
        config: DesktopServiceConfig,
        device_store: DS,
        message_store: MS,
        secret_store: SS,
        token_generator: TokenGenerator,
        events: ES,
        now: N,
    ) -> Self {
        let mut auth = AuthService::new(device_store, token_generator, now);
        let pairing_code = auth.start_pairing();
        tracing::info!(
            service_name = %config.service_name,
            preferred_port = config.preferred_port,
            history_limit = config.history_limit,
            pairing_session_id = %pairing_code.session_id,
            "desktop service initialized"
        );
        Self {
            config,
            auth,
            message_store,
            secret_store,
            events,
            lan_port: None,
            pairing_code,
            secure_pairing: None,
        }
    }

    pub fn status_snapshot(&self) -> StatusSnapshot {
        StatusSnapshot {
            service_name: self.config.service_name.clone(),
            preferred_port: self.config.preferred_port,
            pairing_code: PairingCodeSnapshot::from(&self.pairing_code),
            devices: self.list_devices(),
            latest_messages: self
                .list_messages(self.config.history_limit)
                .unwrap_or_default(),
            transport: TransportSnapshot {
                lan_port: self.lan_port,
                mdns_service_type: "_smspusher._tcp".into(),
                secure_protocol: Some(smspusher_crypto::PROTOCOL_NAME.into()),
                status: if self.lan_port.is_some() {
                    "running".into()
                } else {
                    "stopped".into()
                },
            },
        }
    }

    pub fn refresh_pairing_code(&mut self) -> String {
        self.pairing_code = self.auth.start_pairing();
        self.secure_pairing = None;
        tracing::info!(
            pairing_session_id = %self.pairing_code.session_id,
            expires_at = %self.pairing_code.expires_at,
            "refresh_pairing_code generated new code"
        );
        self.events.emit(ServiceEvent::PairingCodeChanged {
            value: self.pairing_code.value.clone(),
        });
        self.pairing_code.value.clone()
    }

    pub fn pair(&mut self, request: PairRequest) -> Result<PairResponse, ServiceError> {
        let device_name = request.device_name.clone();
        let response = match self.auth.pair(request) {
            Ok(response) => response,
            Err(error) => {
                tracing::warn!(device_name = %device_name, error = %error, "pairing failed");
                return Err(error.into());
            }
        };
        tracing::info!(
            device_id = %response.device_id,
            device_name = %device_name,
            "device paired through legacy route"
        );
        self.events.emit(ServiceEvent::DeviceChanged {
            device_id: response.device_id.clone(),
        });
        self.events.emit(ServiceEvent::StatusChanged);
        Ok(response)
    }

    pub fn refresh(&mut self, request: RefreshRequest) -> Result<RefreshResponse, ServiceError> {
        Ok(self.auth.refresh(request)?)
    }

    pub fn authenticate(&self, device_id: &str, access_token: &str) -> Result<(), AuthError> {
        self.auth.authenticate(device_id, access_token)
    }

    pub fn revoke_device(&mut self, device_id: &str) {
        self.auth.revoke(device_id);
        tracing::info!(device_id = %device_id, "device revoked");
        self.events.emit(ServiceEvent::DeviceChanged {
            device_id: device_id.to_owned(),
        });
        self.events.emit(ServiceEvent::StatusChanged);
    }

    pub fn accept_message(&mut self, message: IncomingMessage) -> Result<bool, ServiceError> {
        let message_id = message.message_id.clone();
        let device_id = message.device_id.clone();
        let sender = message.sender.clone();
        let inserted = self.message_store.insert_if_new(&message)?;
        if inserted {
            tracing::info!(
                message_id = %message_id,
                device_id = %device_id,
                sender = %sender,
                "accept_message stored new message"
            );
            self.events.emit(ServiceEvent::MessageReceived {
                message_id: message.message_id,
                device_id: message.device_id,
            });
            self.events.emit(ServiceEvent::QueueChanged { pending: 0 });
            self.events.emit(ServiceEvent::StatusChanged);
        } else {
            tracing::info!(
                message_id = %message_id,
                device_id = %device_id,
                "accept_message skipped duplicate message"
            );
        }
        Ok(inserted)
    }

    pub fn start_secure_pairing(
        &mut self,
        request: PairV2StartRequest,
    ) -> Result<PairV2StartResponse, ServiceError> {
        if request.protocol != smspusher_crypto::PROTOCOL_NAME {
            tracing::warn!(
                protocol = %request.protocol,
                expected_protocol = smspusher_crypto::PROTOCOL_NAME,
                "secure pairing start rejected unsupported protocol"
            );
            return Err(AuthError::InvalidPairingSession.into());
        }
        self.ensure_secure_pairing_session(&request.pairing_session_id)?;
        tracing::info!(
            pairing_session_id = %request.pairing_session_id,
            client_instance_id = %request.client_instance_id,
            device_name = %request.device_name,
            "secure pairing start accepted"
        );
        let transcript =
            self.secure_pairing_transcript(&request.client_instance_id, &request.device_name);
        let client_msg = STANDARD
            .decode(&request.client_pake_message)
            .map_err(|_| AuthError::InvalidSecureEnvelope)?;
        let mut server = PakeExchange::new(PakeRole::Server, &transcript)
            .map_err(|_| AuthError::PairingConfirmationFailed)?;
        let server_msg = server
            .generate_message(&self.pairing_code.value)
            .map_err(|_| AuthError::PairingConfirmationFailed)?;
        let pairing_key = server
            .process_message(&client_msg)
            .map_err(|_| AuthError::PairingConfirmationFailed)?;
        let server_confirm = pairing_key
            .confirmation_mac("server", b"start")
            .map_err(|_| AuthError::PairingConfirmationFailed)?;
        let session_key_id = format!("pair_{}", random_hex(12));

        if let Some(session) = &mut self.secure_pairing {
            session.server_message = Some(server_msg.clone());
            session.pairing_key = Some(pairing_key.as_bytes().to_vec());
            session.key_id = Some(session_key_id.clone());
            session.client_instance_id = Some(request.client_instance_id);
            session.device_name = Some(request.device_name);
        }

        Ok(PairV2StartResponse {
            protocol: smspusher_crypto::PROTOCOL_NAME.into(),
            pairing_session_id: self.pairing_code.session_id.clone(),
            server_pake_message: STANDARD.encode(server_msg),
            server_confirm: STANDARD.encode(server_confirm),
            key_id: session_key_id,
            expires_at: self.pairing_code.expires_at,
        })
    }

    pub fn finish_secure_pairing(
        &mut self,
        request: PairV2FinishRequest,
    ) -> Result<PairV2FinishResponse, ServiceError> {
        if request.protocol != smspusher_crypto::PROTOCOL_NAME {
            tracing::warn!(
                protocol = %request.protocol,
                expected_protocol = smspusher_crypto::PROTOCOL_NAME,
                "finish_secure_pairing rejected unsupported protocol"
            );
            return Err(AuthError::InvalidPairingSession.into());
        }
        self.ensure_secure_pairing_session(&request.pairing_session_id)?;
        let (pairing_key, session_key_id) = {
            let session = self
                .secure_pairing
                .as_ref()
                .ok_or(AuthError::InvalidPairingSession)?;
            let pairing_key = session
                .pairing_key
                .clone()
                .ok_or(AuthError::InvalidPairingSession)?;
            let session_key_id = session
                .key_id
                .clone()
                .ok_or(AuthError::InvalidPairingSession)?;
            (pairing_key, session_key_id)
        };
        let expected_client_confirm =
            smspusher_crypto::hkdf::hkdf_sha256(&pairing_key, b"client", b"finish", 32)
                .map_err(|_| AuthError::PairingConfirmationFailed)?;
        if request.client_confirm != STANDARD.encode(expected_client_confirm) {
            if let Some(session) = &mut self.secure_pairing {
                session.record_failure();
            }
            tracing::warn!(
                pairing_session_id = %request.pairing_session_id,
                "finish_secure_pairing rejected client confirmation"
            );
            return Err(AuthError::PairingConfirmationFailed.into());
        }

        let finish_aad = SecureAad::new(
            "POST",
            "/pair/v2/finish",
            "pairing-session",
            &session_key_id,
            0,
        );
        let plain = open_envelope(
            &pairing_key,
            &request.encrypted_pair_request,
            finish_aad.as_bytes(),
        )
        .map_err(|_| AuthError::DecryptFailed)?;
        let plain_request: PairV2PlainRequest =
            serde_json::from_slice(&plain).map_err(|_| AuthError::InvalidSecureEnvelope)?;
        let paired_at = self.auth.now();
        let existing = self
            .auth
            .device_store()
            .device_by_client_instance_id(&plain_request.client_instance_id);
        let device_id = existing
            .as_ref()
            .map(|device| device.device_id.clone())
            .unwrap_or_else(|| format!("dev_{}", random_hex(12)));
        let device_key_id = format!("key_{}", random_hex(12));
        let mut device_secret = [0u8; smspusher_crypto::DEVICE_SECRET_LEN];
        OsRng.fill_bytes(&mut device_secret);
        self.secret_store
            .save_device_secret(&device_id, &device_key_id, &device_secret)?;

        let device = StoredDevice {
            device_id: device_id.clone(),
            device_name: plain_request.device_name.clone(),
            client_instance_id: Some(plain_request.client_instance_id),
            access_token_hash: String::new(),
            access_token_expires_at: paired_at,
            refresh_token_hash: String::new(),
            refresh_token_expires_at: paired_at,
            revoked: false,
            secure_transport_version: Some(2),
            key_id: Some(device_key_id.clone()),
            highest_counter: 0,
            paired_at: Some(paired_at),
            last_seen_at: Some(paired_at),
        };
        if existing.is_some() {
            self.auth.device_store_mut().update(device);
        } else {
            self.auth.device_store_mut().save(device);
        }

        let plain_response = PairV2PlainResponse {
            device_id: device_id.clone(),
            key_id: device_key_id.clone(),
            device_secret: STANDARD.encode(device_secret),
            desktop_device_name: self.config.service_name.clone(),
            paired_at,
        };
        let response_aad = SecureAad::new(
            "POST",
            "/pair/v2/finish/response",
            "pairing-session",
            &session_key_id,
            1,
        );
        let encrypted_pair_response = seal_envelope(
            &pairing_key,
            "pairing-session",
            &session_key_id,
            1,
            response_aad.as_bytes(),
            &serde_json::to_vec(&plain_response).map_err(|_| AuthError::InvalidSecureEnvelope)?,
        )
        .map_err(|_| AuthError::InvalidSecureEnvelope)?;

        self.secure_pairing = None;
        tracing::info!(
            device_id = %device_id,
            device_key_id = %device_key_id,
            "finish_secure_pairing created secure device"
        );
        self.events.emit(ServiceEvent::DeviceChanged { device_id });
        self.events.emit(ServiceEvent::StatusChanged);

        Ok(PairV2FinishResponse {
            encrypted_pair_response,
        })
    }

    pub fn authenticate_secure_envelope(
        &mut self,
        path: &str,
        envelope: &smspusher_crypto::SecureEnvelope,
    ) -> Result<Vec<u8>, ServiceError> {
        let device = self
            .auth
            .device_store()
            .device(&envelope.device_id)
            .ok_or_else(|| {
                tracing::warn!(
                    device_id = %envelope.device_id,
                    path,
                    "secure envelope rejected unknown device"
                );
                AuthError::UnknownDevice
            })?;
        if device.revoked {
            tracing::warn!(device_id = %envelope.device_id, path, "secure envelope rejected revoked device");
            return Err(AuthError::DeviceRevoked.into());
        }
        if !device.supports_secure_transport()
            || device.key_id.as_deref() != Some(envelope.key_id.as_str())
        {
            tracing::warn!(
                device_id = %envelope.device_id,
                key_id = %envelope.key_id,
                path,
                "secure envelope rejected invalid key"
            );
            return Err(AuthError::InvalidKey.into());
        }
        if envelope.counter <= device.highest_counter {
            tracing::warn!(
                device_id = %envelope.device_id,
                counter = envelope.counter,
                highest_counter = device.highest_counter,
                path,
                "secure envelope replay detected"
            );
            return Err(AuthError::ReplayDetected.into());
        }
        let secret = self
            .secret_store
            .load_device_secret(&envelope.device_id, &envelope.key_id)?;
        let purpose = if path == "/secure/messages" {
            "message"
        } else {
            "auth-check"
        };
        let key = smspusher_crypto::derive_device_key(&secret, &envelope.key_id, purpose)
            .map_err(|_| AuthError::InvalidKey)?;
        let aad = SecureAad::new(
            "POST",
            path,
            &envelope.device_id,
            &envelope.key_id,
            envelope.counter,
        );
        let plain =
            open_envelope(&key, envelope, aad.as_bytes()).map_err(|_| AuthError::DecryptFailed)?;
        self.auth
            .device_store_mut()
            .update_highest_counter(&envelope.device_id, envelope.counter);
        tracing::debug!(
            device_id = %envelope.device_id,
            key_id = %envelope.key_id,
            counter = envelope.counter,
            path,
            "secure envelope authenticated"
        );
        Ok(plain)
    }

    pub fn accept_secure_message_envelope(
        &mut self,
        envelope: smspusher_crypto::SecureEnvelope,
    ) -> Result<bool, ServiceError> {
        let device_id = envelope.device_id.clone();
        let counter = envelope.counter;
        let plain = self.authenticate_secure_envelope("/secure/messages", &envelope)?;
        let request: SecureMessagePlainRequest =
            serde_json::from_slice(&plain).map_err(|_| AuthError::InvalidSecureEnvelope)?;
        tracing::info!(
            device_id = %device_id,
            message_id = %request.message_id,
            counter,
            "secure message envelope accepted"
        );
        self.accept_message(IncomingMessage {
            message_id: request.message_id,
            sender: request.sender,
            body: request.body,
            received_at: request.received_at,
            subscription_id: request.subscription_id,
            device_id,
        })
    }

    pub fn list_devices(&self) -> Vec<DeviceSnapshot> {
        self.auth
            .device_store()
            .all_devices()
            .into_iter()
            .map(|device| DeviceSnapshot {
                device_id: device.device_id,
                device_name: device.device_name,
                revoked: device.revoked,
                secure_transport_version: device.secure_transport_version,
                key_id: device.key_id,
            })
            .collect()
    }

    pub fn list_messages(&self, limit: usize) -> Result<Vec<MessageSnapshot>, ServiceError> {
        Ok(self
            .message_store
            .latest(limit)?
            .into_iter()
            .map(|message| {
                let verification_code =
                    VerificationCodeExtractor::extract(&message.body).map(|code| code.value);
                MessageSnapshot {
                    message_id: message.message_id,
                    sender: message.sender,
                    body: message.body,
                    received_at: message.received_at,
                    subscription_id: message.subscription_id,
                    device_id: message.device_id,
                    verification_code,
                }
            })
            .collect())
    }

    pub fn set_lan_port(&mut self, port: Option<u16>) {
        self.lan_port = port;
        tracing::info!(lan_port = ?port, "LAN transport status changed");
        self.events.emit(ServiceEvent::TransportChanged {
            status: if port.is_some() {
                "running".into()
            } else {
                "stopped".into()
            },
        });
    }

    fn ensure_secure_pairing_session(&mut self, session_id: &str) -> Result<(), AuthError> {
        if session_id != self.pairing_code.session_id {
            return Err(AuthError::InvalidPairingSession);
        }
        if !self.pairing_code.is_valid(self.auth.now()) {
            return Err(AuthError::PairingCodeExpired);
        }
        let reset = self
            .secure_pairing
            .as_ref()
            .map(|session| session.session_id != session_id)
            .unwrap_or(true);
        if reset {
            self.secure_pairing = Some(ActiveSecurePairing::new(
                self.pairing_code.session_id.clone(),
                self.pairing_code.expires_at,
            ));
        }
        let session = self
            .secure_pairing
            .as_ref()
            .ok_or(AuthError::InvalidPairingSession)?;
        if session.failed_attempts >= ActiveSecurePairing::MAX_FAILED_ATTEMPTS {
            return Err(AuthError::TooManyPairingAttempts);
        }
        if !session.is_valid(self.auth.now()) {
            return Err(AuthError::PairingCodeExpired);
        }
        Ok(())
    }

    fn secure_pairing_transcript(
        &self,
        android_client_instance_id: &str,
        android_device_name: &str,
    ) -> PairingTranscript {
        PairingTranscript::new(
            &self.pairing_code.session_id,
            &self.config.service_name,
            &self.config.service_name,
            android_client_instance_id,
            android_device_name,
            "",
            &self.pairing_code.expires_at.to_rfc3339(),
        )
    }
}

fn random_hex(bytes_len: usize) -> String {
    let mut bytes = vec![0u8; bytes_len];
    OsRng.fill_bytes(&mut bytes);
    hex::encode(bytes)
}

#[cfg(test)]
mod secure_runtime_tests {
    use base64::{engine::general_purpose::STANDARD, Engine as _};
    use chrono::{TimeZone, Utc};
    use smspusher_core::{
        InMemoryDeviceStore, InMemorySecretStore, PairV2FinishRequest, PairV2PlainRequest,
        PairV2PlainResponse, PairV2StartRequest, SecureMessagePlainRequest, SqliteMessageStore,
        TokenGenerator,
    };
    use smspusher_crypto::{
        open_envelope, seal_envelope, PairingTranscript, PakeExchange, PakeRole, SecureAad,
    };

    use super::*;
    use crate::VecEventSink;

    fn now() -> chrono::DateTime<Utc> {
        Utc.with_ymd_and_hms(2026, 6, 15, 8, 0, 0).unwrap()
    }

    fn test_service() -> DesktopService<
        InMemoryDeviceStore,
        SqliteMessageStore,
        VecEventSink,
        fn() -> chrono::DateTime<Utc>,
        InMemorySecretStore,
    > {
        DesktopService::new_for_tests_with_secrets(
            DesktopServiceConfig {
                service_name: "Test Desktop".into(),
                preferred_port: 55515,
                history_limit: 10,
            },
            InMemoryDeviceStore::default(),
            SqliteMessageStore::open_in_memory(10).unwrap(),
            InMemorySecretStore::default(),
            TokenGenerator::seeded(11),
            VecEventSink::default(),
            now,
        )
    }

    fn transcript(
        service: &DesktopService<
            InMemoryDeviceStore,
            SqliteMessageStore,
            VecEventSink,
            fn() -> chrono::DateTime<Utc>,
            InMemorySecretStore,
        >,
        android_name: &str,
        android_client_id: &str,
    ) -> PairingTranscript {
        let status = service.status_snapshot();
        PairingTranscript::new(
            &status.pairing_code.session_id,
            "Test Desktop",
            "Test Desktop",
            android_client_id,
            android_name,
            "",
            &status.pairing_code.expires_at.to_rfc3339(),
        )
    }

    fn pair_secure(
        service: &mut DesktopService<
            InMemoryDeviceStore,
            SqliteMessageStore,
            VecEventSink,
            fn() -> chrono::DateTime<Utc>,
            InMemorySecretStore,
        >,
    ) -> PairV2PlainResponse {
        let status = service.status_snapshot();
        let pairing_code = status.pairing_code.value.clone();
        let pairing_session_id = status.pairing_code.session_id.clone();
        let transcript = transcript(service, "Test Android Device", "android-1");
        let mut client = PakeExchange::new(PakeRole::Client, &transcript).unwrap();
        let client_msg = client.generate_message(&pairing_code).unwrap();
        let start = service
            .start_secure_pairing(PairV2StartRequest {
                protocol: smspusher_crypto::PROTOCOL_NAME.into(),
                pairing_session_id,
                client_instance_id: "android-1".into(),
                device_name: "Test Android Device".into(),
                client_pake_message: STANDARD.encode(client_msg),
            })
            .unwrap();
        let server_msg = STANDARD.decode(&start.server_pake_message).unwrap();
        let client_key = client.process_message(&server_msg).unwrap();
        assert_eq!(
            STANDARD.encode(client_key.confirmation_mac("server", b"start").unwrap()),
            start.server_confirm
        );

        let plain_request = PairV2PlainRequest {
            device_name: "Test Android Device".into(),
            client_instance_id: "android-1".into(),
            client_version: 2,
        };
        let finish_aad = SecureAad::new(
            "POST",
            "/pair/v2/finish",
            "pairing-session",
            &start.key_id,
            0,
        );
        let encrypted_pair_request = seal_envelope(
            client_key.as_bytes(),
            "pairing-session",
            &start.key_id,
            0,
            finish_aad.as_bytes(),
            &serde_json::to_vec(&plain_request).unwrap(),
        )
        .unwrap();
        let finish = service
            .finish_secure_pairing(PairV2FinishRequest {
                protocol: smspusher_crypto::PROTOCOL_NAME.into(),
                pairing_session_id: start.pairing_session_id,
                client_confirm: STANDARD
                    .encode(client_key.confirmation_mac("client", b"finish").unwrap()),
                encrypted_pair_request,
            })
            .unwrap();
        let response_aad = SecureAad::new(
            "POST",
            "/pair/v2/finish/response",
            "pairing-session",
            &finish.encrypted_pair_response.key_id,
            1,
        );
        let plain = open_envelope(
            client_key.as_bytes(),
            &finish.encrypted_pair_response,
            response_aad.as_bytes(),
        )
        .unwrap();
        serde_json::from_slice(&plain).unwrap()
    }

    #[test]
    fn secure_pairing_start_and_finish_create_secure_device() {
        let mut service = test_service();

        let paired = pair_secure(&mut service);

        assert!(paired.device_id.starts_with("dev_"));
        assert!(!paired.key_id.is_empty());
        assert!(!paired.device_secret.is_empty());
        let devices = service.list_devices();
        assert_eq!(devices.len(), 1);
        assert_eq!(devices[0].secure_transport_version, Some(2));
        assert_eq!(devices[0].key_id.as_deref(), Some(paired.key_id.as_str()));
    }

    #[test]
    fn replay_counter_rejects_duplicate_secure_message_counter() {
        let mut service = test_service();
        let paired = pair_secure(&mut service);
        let device_secret = STANDARD.decode(&paired.device_secret).unwrap();
        let message_key =
            smspusher_crypto::derive_device_key(&device_secret, &paired.key_id, "message").unwrap();
        let aad = SecureAad::new(
            "POST",
            "/secure/messages",
            &paired.device_id,
            &paired.key_id,
            1,
        );
        let plain = SecureMessagePlainRequest {
            message_id: "msg_1".into(),
            sender: "TEST-SENDER".into(),
            body: "test verification code 135790".into(),
            received_at: now(),
            subscription_id: 1,
        };
        let envelope = seal_envelope(
            &message_key,
            &paired.device_id,
            &paired.key_id,
            1,
            aad.as_bytes(),
            &serde_json::to_vec(&plain).unwrap(),
        )
        .unwrap();

        assert!(service
            .accept_secure_message_envelope(envelope.clone())
            .unwrap());
        let replay = service.accept_secure_message_envelope(envelope);

        assert_eq!(
            replay.unwrap_err().to_string(),
            "auth error: replay_detected"
        );
    }
}
