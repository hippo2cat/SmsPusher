use axum::{
    body::Bytes,
    extract::State,
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
    Router,
};
use serde::Serialize;
use smspusher_core::{
    json, AuthCheckRequest, AuthError, DeviceStore, IncomingMessage, MessageStore, PairRequest,
    RefreshRequest, SecretStore, SecureAuthCheckPlainRequest,
};
use smspusher_crypto::SecureEnvelope;
use smspusher_service::{DesktopService, ServiceError, ServiceEventSink};
use std::sync::{Arc, Mutex};

pub type SharedLanService<DS, MS, ES, N, SS = smspusher_core::InMemorySecretStore> =
    Arc<Mutex<DesktopService<DS, MS, ES, N, SS>>>;

type LanHttpState<DS, MS, ES, N, SS = smspusher_core::InMemorySecretStore> =
    (SharedLanService<DS, MS, ES, N, SS>, LanRouterOptions);

#[derive(Clone, Copy)]
struct LanRouterOptions {
    allow_v1_routes: bool,
}

impl Default for LanRouterOptions {
    fn default() -> Self {
        Self {
            allow_v1_routes: false,
        }
    }
}

pub fn shared_lan_service<DS, MS, ES, N, SS>(
    service: DesktopService<DS, MS, ES, N, SS>,
) -> SharedLanService<DS, MS, ES, N, SS>
where
    DS: DeviceStore,
    MS: MessageStore,
    SS: SecretStore,
    ES: ServiceEventSink,
    N: Fn() -> chrono::DateTime<chrono::Utc>,
{
    Arc::new(Mutex::new(service))
}

pub fn lan_router<DS, MS, ES, N, SS>(service: DesktopService<DS, MS, ES, N, SS>) -> Router
where
    DS: DeviceStore + Send + 'static,
    MS: MessageStore + Send + 'static,
    SS: SecretStore + Send + 'static,
    ES: ServiceEventSink,
    N: Fn() -> chrono::DateTime<chrono::Utc> + Send + Sync + 'static,
{
    lan_router_with_shared_service(shared_lan_service(service))
}

pub fn lan_router_allowing_v1_for_tests<DS, MS, ES, N, SS>(
    service: DesktopService<DS, MS, ES, N, SS>,
) -> Router
where
    DS: DeviceStore + Send + 'static,
    MS: MessageStore + Send + 'static,
    SS: SecretStore + Send + 'static,
    ES: ServiceEventSink,
    N: Fn() -> chrono::DateTime<chrono::Utc> + Send + Sync + 'static,
{
    lan_router_with_shared_service_allowing_v1_for_tests(shared_lan_service(service))
}

pub fn lan_router_with_shared_service<DS, MS, ES, N, SS>(
    state: SharedLanService<DS, MS, ES, N, SS>,
) -> Router
where
    DS: DeviceStore + Send + 'static,
    MS: MessageStore + Send + 'static,
    SS: SecretStore + Send + 'static,
    ES: ServiceEventSink,
    N: Fn() -> chrono::DateTime<chrono::Utc> + Send + Sync + 'static,
{
    lan_router_with_shared_service_and_options(state, LanRouterOptions::default())
}

pub fn lan_router_with_shared_service_allowing_v1_for_tests<DS, MS, ES, N, SS>(
    state: SharedLanService<DS, MS, ES, N, SS>,
) -> Router
where
    DS: DeviceStore + Send + 'static,
    MS: MessageStore + Send + 'static,
    SS: SecretStore + Send + 'static,
    ES: ServiceEventSink,
    N: Fn() -> chrono::DateTime<chrono::Utc> + Send + Sync + 'static,
{
    lan_router_with_shared_service_and_options(
        state,
        LanRouterOptions {
            allow_v1_routes: true,
        },
    )
}

fn lan_router_with_shared_service_and_options<DS, MS, ES, N, SS>(
    state: SharedLanService<DS, MS, ES, N, SS>,
    options: LanRouterOptions,
) -> Router
where
    DS: DeviceStore + Send + 'static,
    MS: MessageStore + Send + 'static,
    SS: SecretStore + Send + 'static,
    ES: ServiceEventSink,
    N: Fn() -> chrono::DateTime<chrono::Utc> + Send + Sync + 'static,
{
    Router::new()
        .route("/health", get(health))
        .route(
            "/pair/v2/session",
            get(pair_v2_session::<DS, MS, ES, N, SS>),
        )
        .route("/pair", post(pair::<DS, MS, ES, N, SS>))
        .route("/pair/v2/start", post(pair_v2_start::<DS, MS, ES, N, SS>))
        .route("/pair/v2/finish", post(pair_v2_finish::<DS, MS, ES, N, SS>))
        .route("/auth/refresh", post(refresh::<DS, MS, ES, N, SS>))
        .route("/auth/check", post(auth_check::<DS, MS, ES, N, SS>))
        .route("/messages", post(messages::<DS, MS, ES, N, SS>))
        .route(
            "/secure/auth/check",
            post(secure_auth_check::<DS, MS, ES, N, SS>),
        )
        .route(
            "/secure/messages",
            post(secure_messages::<DS, MS, ES, N, SS>),
        )
        .fallback(not_found)
        .with_state((state, options))
}

async fn health() -> Response {
    json_response(StatusCode::OK, json::status_body("ok"))
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct PairV2SessionResponse {
    service_name: String,
    secure_protocol: String,
    pairing_session_id: String,
    pairing_expires_at: String,
}

async fn pair_v2_session<DS, MS, ES, N, SS>(
    State((service, _)): State<LanHttpState<DS, MS, ES, N, SS>>,
) -> Response
where
    DS: DeviceStore,
    MS: MessageStore,
    SS: SecretStore,
    ES: ServiceEventSink,
    N: Fn() -> chrono::DateTime<chrono::Utc>,
{
    let service = service.lock().expect("desktop service lock");
    let status = service.status_snapshot();
    let body = PairV2SessionResponse {
        service_name: status.service_name,
        secure_protocol: status
            .transport
            .secure_protocol
            .unwrap_or_else(|| smspusher_crypto::PROTOCOL_NAME.to_owned()),
        pairing_session_id: status.pairing_code.session_id,
        pairing_expires_at: status.pairing_code.expires_at.to_rfc3339(),
    };
    match json::to_string(&body) {
        Ok(body) => json_response(StatusCode::OK, body),
        Err(_) => error_response(StatusCode::BAD_REQUEST, "bad_request"),
    }
}

async fn pair<DS, MS, ES, N, SS>(
    State((service, options)): State<LanHttpState<DS, MS, ES, N, SS>>,
    body: Bytes,
) -> Response
where
    DS: DeviceStore,
    MS: MessageStore,
    SS: SecretStore,
    ES: ServiceEventSink,
    N: Fn() -> chrono::DateTime<chrono::Utc>,
{
    if !options.allow_v1_routes {
        return auth_error_response(AuthError::SecurePairingRequired);
    }
    let request: PairRequest = match json::from_slice(&body) {
        Ok(request) => request,
        Err(_) => return error_response(StatusCode::BAD_REQUEST, "bad_request"),
    };
    let mut service = service.lock().expect("desktop service lock");
    match service.pair(request) {
        Ok(response) => match json::to_string(&response) {
            Ok(body) => json_response(StatusCode::OK, body),
            Err(_) => error_response(StatusCode::BAD_REQUEST, "bad_request"),
        },
        Err(error) => service_error_response(error),
    }
}

async fn pair_v2_start<DS, MS, ES, N, SS>(
    State((service, _)): State<LanHttpState<DS, MS, ES, N, SS>>,
    body: Bytes,
) -> Response
where
    DS: DeviceStore,
    MS: MessageStore,
    SS: SecretStore,
    ES: ServiceEventSink,
    N: Fn() -> chrono::DateTime<chrono::Utc>,
{
    let request: smspusher_core::PairV2StartRequest = match json::from_slice(&body) {
        Ok(request) => request,
        Err(_) => return error_response(StatusCode::BAD_REQUEST, "bad_request"),
    };
    let mut service = service.lock().expect("desktop service lock");
    match service.start_secure_pairing(request) {
        Ok(response) => match json::to_string(&response) {
            Ok(body) => json_response(StatusCode::OK, body),
            Err(_) => error_response(StatusCode::BAD_REQUEST, "bad_request"),
        },
        Err(error) => service_error_response(error),
    }
}

async fn pair_v2_finish<DS, MS, ES, N, SS>(
    State((service, _)): State<LanHttpState<DS, MS, ES, N, SS>>,
    body: Bytes,
) -> Response
where
    DS: DeviceStore,
    MS: MessageStore,
    SS: SecretStore,
    ES: ServiceEventSink,
    N: Fn() -> chrono::DateTime<chrono::Utc>,
{
    let request: smspusher_core::PairV2FinishRequest = match json::from_slice(&body) {
        Ok(request) => request,
        Err(_) => return error_response(StatusCode::BAD_REQUEST, "bad_request"),
    };
    let mut service = service.lock().expect("desktop service lock");
    match service.finish_secure_pairing(request) {
        Ok(response) => match json::to_string(&response) {
            Ok(body) => json_response(StatusCode::OK, body),
            Err(_) => error_response(StatusCode::BAD_REQUEST, "bad_request"),
        },
        Err(error) => service_error_response(error),
    }
}

async fn refresh<DS, MS, ES, N, SS>(
    State((service, options)): State<LanHttpState<DS, MS, ES, N, SS>>,
    body: Bytes,
) -> Response
where
    DS: DeviceStore,
    MS: MessageStore,
    SS: SecretStore,
    ES: ServiceEventSink,
    N: Fn() -> chrono::DateTime<chrono::Utc>,
{
    if !options.allow_v1_routes {
        return auth_error_response(AuthError::SecurePairingRequired);
    }
    let request: RefreshRequest = match json::from_slice(&body) {
        Ok(request) => request,
        Err(_) => return error_response(StatusCode::BAD_REQUEST, "bad_request"),
    };
    let mut service = service.lock().expect("desktop service lock");
    match service.refresh(request) {
        Ok(response) => match json::to_string(&response) {
            Ok(body) => json_response(StatusCode::OK, body),
            Err(_) => error_response(StatusCode::BAD_REQUEST, "bad_request"),
        },
        Err(error) => service_error_response(error),
    }
}

async fn auth_check<DS, MS, ES, N, SS>(
    State((service, options)): State<LanHttpState<DS, MS, ES, N, SS>>,
    headers: HeaderMap,
    body: Bytes,
) -> Response
where
    DS: DeviceStore,
    MS: MessageStore,
    SS: SecretStore,
    ES: ServiceEventSink,
    N: Fn() -> chrono::DateTime<chrono::Utc>,
{
    if !options.allow_v1_routes {
        return auth_error_response(AuthError::SecurePairingRequired);
    }
    let token = match bearer_token(&headers) {
        Some(token) => token,
        None => return error_response(StatusCode::UNAUTHORIZED, "invalid_token"),
    };
    let request: AuthCheckRequest = match json::from_slice(&body) {
        Ok(request) => request,
        Err(_) => return error_response(StatusCode::BAD_REQUEST, "bad_request"),
    };
    let service = service.lock().expect("desktop service lock");
    match service.authenticate(&request.device_id, &token) {
        Ok(()) => json_response(StatusCode::OK, json::status_body("authorized")),
        Err(error) => auth_error_response(error),
    }
}

async fn messages<DS, MS, ES, N, SS>(
    State((service, options)): State<LanHttpState<DS, MS, ES, N, SS>>,
    headers: HeaderMap,
    body: Bytes,
) -> Response
where
    DS: DeviceStore,
    MS: MessageStore,
    SS: SecretStore,
    ES: ServiceEventSink,
    N: Fn() -> chrono::DateTime<chrono::Utc>,
{
    if !options.allow_v1_routes {
        return auth_error_response(AuthError::SecurePairingRequired);
    }
    let token = match bearer_token(&headers) {
        Some(token) => token,
        None => return error_response(StatusCode::UNAUTHORIZED, "invalid_token"),
    };
    let message: IncomingMessage = match json::from_slice(&body) {
        Ok(message) => message,
        Err(_) => return error_response(StatusCode::BAD_REQUEST, "bad_request"),
    };
    let mut service = service.lock().expect("desktop service lock");
    if let Err(error) = service.authenticate(&message.device_id, &token) {
        return auth_error_response(error);
    }
    match service.accept_message(message) {
        Ok(_) => json_response(StatusCode::OK, json::status_body("accepted")),
        Err(_) => error_response(StatusCode::BAD_REQUEST, "bad_request"),
    }
}

async fn secure_auth_check<DS, MS, ES, N, SS>(
    State((service, _)): State<LanHttpState<DS, MS, ES, N, SS>>,
    body: Bytes,
) -> Response
where
    DS: DeviceStore,
    MS: MessageStore,
    SS: SecretStore,
    ES: ServiceEventSink,
    N: Fn() -> chrono::DateTime<chrono::Utc>,
{
    let envelope: SecureEnvelope = match json::from_slice(&body) {
        Ok(envelope) => envelope,
        Err(_) => return error_response(StatusCode::BAD_REQUEST, "bad_request"),
    };
    let mut service = service.lock().expect("desktop service lock");
    let plain = match service.authenticate_secure_envelope("/secure/auth/check", &envelope) {
        Ok(plain) => plain,
        Err(error) => return service_error_response(error),
    };
    let _: SecureAuthCheckPlainRequest = match serde_json::from_slice(&plain) {
        Ok(request) => request,
        Err(_) => return auth_error_response(AuthError::InvalidSecureEnvelope),
    };
    json_response(StatusCode::OK, json::status_body("authorized"))
}

async fn secure_messages<DS, MS, ES, N, SS>(
    State((service, _)): State<LanHttpState<DS, MS, ES, N, SS>>,
    body: Bytes,
) -> Response
where
    DS: DeviceStore,
    MS: MessageStore,
    SS: SecretStore,
    ES: ServiceEventSink,
    N: Fn() -> chrono::DateTime<chrono::Utc>,
{
    let envelope: SecureEnvelope = match json::from_slice(&body) {
        Ok(envelope) => envelope,
        Err(_) => return error_response(StatusCode::BAD_REQUEST, "bad_request"),
    };
    let device_id = envelope.device_id.clone();
    let counter = envelope.counter;
    let mut service = service.lock().expect("desktop service lock");
    match service.accept_secure_message_envelope(envelope) {
        Ok(inserted) => {
            tracing::info!(device_id = %device_id, counter, inserted, "secure_messages accepted");
            json_response(StatusCode::OK, json::status_body("accepted"))
        }
        Err(error) => service_error_response(error),
    }
}

async fn not_found() -> Response {
    error_response(StatusCode::NOT_FOUND, "not_found")
}

fn bearer_token(headers: &HeaderMap) -> Option<String> {
    headers
        .get("authorization")
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.strip_prefix("Bearer "))
        .map(ToOwned::to_owned)
}

fn auth_error_response(error: AuthError) -> Response {
    tracing::warn!(error = %error, "LAN auth request rejected");
    match error {
        AuthError::InvalidPairingCode => {
            error_response(StatusCode::UNAUTHORIZED, "invalid_pairing_code")
        }
        AuthError::PairingCodeExpired => {
            error_response(StatusCode::UNAUTHORIZED, "pairing_code_expired")
        }
        AuthError::InvalidRefreshToken => {
            error_response(StatusCode::UNAUTHORIZED, "invalid_refresh_token")
        }
        AuthError::RefreshTokenExpired => {
            error_response(StatusCode::UNAUTHORIZED, "refresh_token_expired")
        }
        AuthError::AccessTokenExpired => error_response(StatusCode::UNAUTHORIZED, "token_expired"),
        AuthError::DeviceRevoked | AuthError::InvalidAccessToken => {
            error_response(StatusCode::UNAUTHORIZED, "invalid_token")
        }
        AuthError::InvalidPairingSession => {
            error_response(StatusCode::UNAUTHORIZED, "invalid_pairing_session")
        }
        AuthError::TooManyPairingAttempts => {
            error_response(StatusCode::TOO_MANY_REQUESTS, "too_many_pairing_attempts")
        }
        AuthError::PairingConfirmationFailed => {
            error_response(StatusCode::UNAUTHORIZED, "pairing_confirmation_failed")
        }
        AuthError::InvalidSecureEnvelope => {
            error_response(StatusCode::BAD_REQUEST, "invalid_secure_envelope")
        }
        AuthError::ReplayDetected => error_response(StatusCode::CONFLICT, "replay_detected"),
        AuthError::UnknownDevice => error_response(StatusCode::UNAUTHORIZED, "unknown_device"),
        AuthError::InvalidKey => error_response(StatusCode::UNAUTHORIZED, "invalid_key"),
        AuthError::DecryptFailed => error_response(StatusCode::UNAUTHORIZED, "decrypt_failed"),
        AuthError::SecurePairingRequired => {
            error_response(StatusCode::GONE, "secure_pairing_required")
        }
        AuthError::PairingNotStarted => error_response(StatusCode::BAD_REQUEST, "bad_request"),
    }
}

fn service_error_response(error: ServiceError) -> Response {
    tracing::warn!(error = %error, "LAN service request failed");
    match error {
        ServiceError::Auth(error) => auth_error_response(error),
        ServiceError::MessageStore(_) => error_response(StatusCode::BAD_REQUEST, "bad_request"),
        ServiceError::SecretStore(_) => {
            error_response(StatusCode::INTERNAL_SERVER_ERROR, "server_error")
        }
    }
}

fn json_response(status: StatusCode, body: String) -> Response {
    (status, [("content-type", "application/json")], body).into_response()
}

fn error_response(status: StatusCode, code: &str) -> Response {
    tracing::warn!(status = %status, code, "LAN HTTP error response");
    json_response(status, json::error_body(code))
}
