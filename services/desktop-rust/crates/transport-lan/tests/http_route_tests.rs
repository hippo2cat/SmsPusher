use std::sync::{Arc, Mutex};

use axum::{
    body::{to_bytes, Body},
    http::{Request, StatusCode},
    Router,
};
use chrono::{Duration, TimeZone, Utc};
use serde_json::Value;
use smspusher_core::{
    InMemoryDeviceStore, IncomingMessage, PairRequest, SqliteMessageStore, TokenGenerator,
};
use smspusher_service::{DesktopService, DesktopServiceConfig, ServiceEvent, VecEventSink};
use tower::ServiceExt;
use transport_lan::lan_router_allowing_v1_for_tests as lan_router;

type TestService = DesktopService<
    InMemoryDeviceStore,
    SqliteMessageStore,
    VecEventSink,
    Box<dyn Fn() -> chrono::DateTime<Utc> + Send + Sync>,
>;

fn test_service() -> TestService {
    let now = Utc.with_ymd_and_hms(2026, 6, 5, 8, 0, 0).unwrap();
    DesktopService::new_for_tests(
        DesktopServiceConfig {
            service_name: "SmsPusher Test".into(),
            preferred_port: 55515,
            history_limit: 1000,
        },
        InMemoryDeviceStore::default(),
        SqliteMessageStore::open_in_memory(1000).unwrap(),
        TokenGenerator::seeded(41),
        VecEventSink::default(),
        Box::new(move || now),
    )
}

fn test_service_with_time(
    current: Arc<Mutex<chrono::DateTime<Utc>>>,
    sink: VecEventSink,
) -> TestService {
    let now_source = current.clone();
    DesktopService::new_for_tests(
        DesktopServiceConfig {
            service_name: "SmsPusher Test".into(),
            preferred_port: 55515,
            history_limit: 1000,
        },
        InMemoryDeviceStore::default(),
        SqliteMessageStore::open_in_memory(1000).unwrap(),
        TokenGenerator::seeded(42),
        sink,
        Box::new(move || *now_source.lock().expect("test clock lock")),
    )
}

async fn response_text(response: axum::response::Response) -> String {
    String::from_utf8(
        to_bytes(response.into_body(), usize::MAX)
            .await
            .unwrap()
            .to_vec(),
    )
    .unwrap()
}

async fn post_json(app: Router, uri: &str, body: impl Into<Body>) -> axum::response::Response {
    app.oneshot(
        Request::builder()
            .method("POST")
            .uri(uri)
            .header("content-type", "application/json")
            .body(body.into())
            .unwrap(),
    )
    .await
    .unwrap()
}

async fn post_json_with_token(
    app: Router,
    uri: &str,
    token: &str,
    body: impl Into<Body>,
) -> axum::response::Response {
    app.oneshot(
        Request::builder()
            .method("POST")
            .uri(uri)
            .header("authorization", format!("Bearer {token}"))
            .header("content-type", "application/json")
            .body(body.into())
            .unwrap(),
    )
    .await
    .unwrap()
}

fn message_json(device_id: &str) -> String {
    serde_json::to_string(&IncomingMessage {
        message_id: "msg_1".into(),
        sender: "TEST-SENDER".into(),
        body: "测试验证码 135790".into(),
        received_at: Utc.with_ymd_and_hms(2026, 6, 5, 8, 0, 0).unwrap(),
        subscription_id: 1,
        device_id: device_id.into(),
    })
    .unwrap()
}

#[tokio::test]
async fn health_route_returns_ok_body() {
    let app = lan_router(test_service());

    let response = app
        .oneshot(
            Request::builder()
                .uri("/health")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(response_text(response).await, r#"{"status":"ok"}"#);
}

#[tokio::test]
async fn pair_v2_session_route_returns_current_pairing_session() {
    let service = test_service();
    let status = service.status_snapshot();
    let app = lan_router(service);

    let response = app
        .oneshot(
            Request::builder()
                .uri("/pair/v2/session")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);
    let body: Value = serde_json::from_str(&response_text(response).await).unwrap();
    assert_eq!(body["serviceName"], "SmsPusher Test");
    assert_eq!(body["secureProtocol"], "lan-secure-v2");
    assert_eq!(body["pairingSessionId"], status.pairing_code.session_id);
    assert_eq!(
        body["pairingExpiresAt"],
        status.pairing_code.expires_at.to_rfc3339()
    );
}

#[tokio::test]
async fn pair_route_returns_android_token_bundle() {
    let service = test_service();
    let code = service.status_snapshot().pairing_code.value;
    let app = lan_router(service);
    let body = format!(r#"{{"pairingCode":"{code}","deviceName":"Test Android Device","clientVersion":1}}"#);

    let response = post_json(app, "/pair", body).await;

    assert_eq!(response.status(), StatusCode::OK);
    let body = response_text(response).await;
    assert!(body.contains(r#""deviceId":"dev_"#), "{body}");
    assert!(body.contains(r#""accessToken":"#), "{body}");
    assert!(body.contains(r#""refreshToken":"#), "{body}");
}

#[tokio::test]
async fn shared_router_mutates_service_visible_to_owner() {
    let service = test_service();
    let code = service.status_snapshot().pairing_code.value;
    let shared = transport_lan::shared_lan_service(service);
    let app = transport_lan::lan_router_with_shared_service_allowing_v1_for_tests(shared.clone());
    let body = format!(r#"{{"pairingCode":"{code}","deviceName":"Test Android Device","clientVersion":1}}"#);

    let response = post_json(app, "/pair", body).await;

    assert_eq!(response.status(), StatusCode::OK);
    let devices = shared.lock().expect("shared service lock").list_devices();
    assert_eq!(devices.len(), 1);
    assert_eq!(devices[0].device_name, "Test Android Device");
}

#[tokio::test]
async fn invalid_pairing_code_returns_existing_error_shape() {
    let app = lan_router(test_service());

    let response = post_json(
        app,
        "/pair",
        r#"{"pairingCode":"000000","deviceName":"Test Android Device","clientVersion":1}"#,
    )
    .await;

    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    assert_eq!(
        response_text(response).await,
        r#"{"error":"invalid_pairing_code"}"#
    );
}

#[tokio::test]
async fn expired_access_token_returns_token_expired() {
    let current = Arc::new(Mutex::new(
        Utc.with_ymd_and_hms(2026, 6, 5, 8, 0, 0).unwrap(),
    ));
    let mut service = test_service_with_time(current.clone(), VecEventSink::default());
    let code = service.status_snapshot().pairing_code.value;
    let paired = service
        .pair(PairRequest {
            pairing_code: code,
            device_name: "Test Android Device".into(),
            client_version: 1,
            client_instance_id: None,
        })
        .unwrap();
    *current.lock().expect("test clock lock") += Duration::seconds(86_500);
    let app = lan_router(service);

    let response = post_json_with_token(
        app,
        "/messages",
        &paired.access_token,
        message_json(&paired.device_id),
    )
    .await;

    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    assert_eq!(
        response_text(response).await,
        r#"{"error":"token_expired"}"#
    );
}

#[tokio::test]
async fn refresh_route_rotates_token_and_rejects_old_refresh_token() {
    let current = Arc::new(Mutex::new(
        Utc.with_ymd_and_hms(2026, 6, 5, 8, 0, 0).unwrap(),
    ));
    let mut service = test_service_with_time(current.clone(), VecEventSink::default());
    let code = service.status_snapshot().pairing_code.value;
    let paired = service
        .pair(PairRequest {
            pairing_code: code,
            device_name: "Test Android Device".into(),
            client_version: 1,
            client_instance_id: None,
        })
        .unwrap();
    *current.lock().expect("test clock lock") += Duration::seconds(86_500);
    let app = lan_router(service);
    let body = format!(
        r#"{{"deviceId":"{}","refreshToken":"{}"}}"#,
        paired.device_id, paired.refresh_token
    );

    let response = post_json(app.clone(), "/auth/refresh", body.clone()).await;
    assert_eq!(response.status(), StatusCode::OK);
    let refreshed: Value = serde_json::from_str(&response_text(response).await).unwrap();
    assert_ne!(
        refreshed["refreshToken"].as_str().unwrap(),
        paired.refresh_token
    );

    let response = post_json(app, "/auth/refresh", body).await;
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    assert_eq!(
        response_text(response).await,
        r#"{"error":"invalid_refresh_token"}"#
    );
}

#[tokio::test]
async fn auth_check_rejects_revoked_device() {
    let mut service = test_service();
    let code = service.status_snapshot().pairing_code.value;
    let paired = service
        .pair(PairRequest {
            pairing_code: code,
            device_name: "Test Android Device".into(),
            client_version: 1,
            client_instance_id: None,
        })
        .unwrap();
    service.revoke_device(&paired.device_id);
    let app = lan_router(service);

    let response = post_json_with_token(
        app,
        "/auth/check",
        &paired.access_token,
        format!(r#"{{"deviceId":"{}"}}"#, paired.device_id),
    )
    .await;

    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    assert_eq!(
        response_text(response).await,
        r#"{"error":"invalid_token"}"#
    );
}

#[tokio::test]
async fn message_route_accepts_authorized_sms() {
    let mut service = test_service();
    let code = service.status_snapshot().pairing_code.value;
    let paired = service
        .pair(PairRequest {
            pairing_code: code,
            device_name: "Test Android Device".into(),
            client_version: 1,
            client_instance_id: None,
        })
        .unwrap();
    let app = lan_router(service);

    let response = post_json_with_token(
        app,
        "/messages",
        &paired.access_token,
        message_json(&paired.device_id),
    )
    .await;

    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(response_text(response).await, r#"{"status":"accepted"}"#);
}

#[tokio::test]
async fn duplicate_message_does_not_emit_second_message_event() {
    let sink = VecEventSink::default();
    let mut service = test_service_with_time(
        Arc::new(Mutex::new(
            Utc.with_ymd_and_hms(2026, 6, 5, 8, 0, 0).unwrap(),
        )),
        sink.clone(),
    );
    let code = service.status_snapshot().pairing_code.value;
    let paired = service
        .pair(PairRequest {
            pairing_code: code,
            device_name: "Test Android Device".into(),
            client_version: 1,
            client_instance_id: None,
        })
        .unwrap();
    let app = lan_router(service);
    let body = message_json(&paired.device_id);

    let first =
        post_json_with_token(app.clone(), "/messages", &paired.access_token, body.clone()).await;
    let second = post_json_with_token(app, "/messages", &paired.access_token, body).await;

    assert_eq!(first.status(), StatusCode::OK);
    assert_eq!(second.status(), StatusCode::OK);
    let message_events = sink
        .events()
        .into_iter()
        .filter(|event| matches!(event, ServiceEvent::MessageReceived { .. }))
        .count();
    assert_eq!(message_events, 1);
}

#[tokio::test]
async fn unknown_route_returns_not_found() {
    let app = lan_router(test_service());

    let response = app
        .oneshot(
            Request::builder()
                .uri("/missing")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::NOT_FOUND);
    assert_eq!(response_text(response).await, r#"{"error":"not_found"}"#);
}
