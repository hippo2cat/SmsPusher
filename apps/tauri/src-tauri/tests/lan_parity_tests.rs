use base64::{engine::general_purpose::STANDARD, Engine as _};
use chrono::{TimeZone, Utc};
use smspusher_core::{
    PairV2FinishRequest, PairV2FinishResponse, PairV2PlainRequest, PairV2PlainResponse,
    PairV2StartRequest, PairV2StartResponse, SecureMessagePlainRequest,
};
use smspusher_crypto::{
    derive_device_key, open_envelope, seal_envelope, PairingTranscript, PakeExchange, PakeRole,
    SecureAad,
};
use smspusher_service::ServiceEvent;
use smspusher_tauri_lib::app_state::{AppSettingsUpdate, SmsPusherAppState};

struct HttpResponse {
    status: reqwest::StatusCode,
    body: String,
}

async fn post_json(
    client: &reqwest::Client,
    port: u16,
    path: &str,
    token: Option<&str>,
    body: String,
) -> HttpResponse {
    let url = format!("http://127.0.0.1:{port}{path}");
    let mut request = client
        .post(url)
        .header(reqwest::header::CONTENT_TYPE, "application/json")
        .body(body);
    if let Some(token) = token {
        request = request.bearer_auth(token);
    }
    let response = request.send().await.unwrap();
    let status = response.status();
    let body = response.text().await.unwrap();
    HttpResponse { status, body }
}

fn pair_body(pairing_code: &str) -> String {
    format!(
        r#"{{"pairingCode":"{pairing_code}","deviceName":"Test Android Device","clientVersion":1,"clientInstanceId":"android-client-1"}}"#
    )
}

struct TestSecureClient {
    client_instance_id: String,
    device_name: String,
    pairing_session_id: String,
    exchange: PakeExchange,
    pairing_key: Option<Vec<u8>>,
    paired: Option<PairV2PlainResponse>,
}

impl TestSecureClient {
    fn new(
        client_instance_id: &str,
        device_name: &str,
        desktop_device_name: &str,
        pairing_session_id: &str,
        pairing_expires_at: &str,
    ) -> Self {
        let transcript = PairingTranscript::new(
            pairing_session_id,
            desktop_device_name,
            desktop_device_name,
            client_instance_id,
            device_name,
            "",
            pairing_expires_at,
        );
        let exchange = PakeExchange::new(PakeRole::Client, &transcript).unwrap();
        Self {
            client_instance_id: client_instance_id.to_owned(),
            device_name: device_name.to_owned(),
            pairing_session_id: pairing_session_id.to_owned(),
            exchange,
            pairing_key: None,
            paired: None,
        }
    }

    fn start_body(&mut self, pairing_code: &str) -> String {
        let client_message = self.exchange.generate_message(pairing_code).unwrap();
        serde_json::to_string(&PairV2StartRequest {
            protocol: smspusher_crypto::PROTOCOL_NAME.to_owned(),
            pairing_session_id: self.pairing_session_id.clone(),
            client_instance_id: self.client_instance_id.clone(),
            device_name: self.device_name.clone(),
            client_pake_message: STANDARD.encode(client_message),
        })
        .unwrap()
    }

    fn finish_body(&mut self, start_body: &str) -> String {
        let start: PairV2StartResponse = serde_json::from_str(start_body).unwrap();
        let server_message = STANDARD.decode(&start.server_pake_message).unwrap();
        let client_key = self.exchange.process_message(&server_message).unwrap();
        assert_eq!(
            STANDARD.encode(client_key.confirmation_mac("server", b"start").unwrap()),
            start.server_confirm
        );
        let plain_request = PairV2PlainRequest {
            device_name: self.device_name.clone(),
            client_instance_id: self.client_instance_id.clone(),
            client_version: 2,
        };
        let aad = SecureAad::new(
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
            aad.as_bytes(),
            &serde_json::to_vec(&plain_request).unwrap(),
        )
        .unwrap();
        self.pairing_key = Some(client_key.as_bytes().to_vec());
        serde_json::to_string(&PairV2FinishRequest {
            protocol: smspusher_crypto::PROTOCOL_NAME.to_owned(),
            pairing_session_id: start.pairing_session_id,
            client_confirm: STANDARD
                .encode(client_key.confirmation_mac("client", b"finish").unwrap()),
            encrypted_pair_request,
        })
        .unwrap()
    }

    fn message_body(&mut self, finish_body: &str, counter: u64, message_id: &str) -> String {
        if self.paired.is_none() {
            let finish: PairV2FinishResponse = serde_json::from_str(finish_body).unwrap();
            let pairing_key = self.pairing_key.as_ref().unwrap();
            let aad = SecureAad::new(
                "POST",
                "/pair/v2/finish/response",
                "pairing-session",
                &finish.encrypted_pair_response.key_id,
                1,
            );
            let plain = open_envelope(pairing_key, &finish.encrypted_pair_response, aad.as_bytes())
                .unwrap();
            self.paired = Some(serde_json::from_slice(&plain).unwrap());
        }
        let paired = self.paired.as_ref().unwrap();
        let device_secret = STANDARD.decode(&paired.device_secret).unwrap();
        let key = derive_device_key(&device_secret, &paired.key_id, "message").unwrap();
        let aad = SecureAad::new(
            "POST",
            "/secure/messages",
            &paired.device_id,
            &paired.key_id,
            counter,
        );
        let plain = SecureMessagePlainRequest {
            message_id: message_id.to_owned(),
            sender: "TEST-SENDER".to_owned(),
            body: "Your test verification code is 135790".to_owned(),
            received_at: Utc.with_ymd_and_hms(2026, 6, 5, 8, 5, 12).unwrap(),
            subscription_id: 1,
        };
        serde_json::to_string(
            &seal_envelope(
                &key,
                &paired.device_id,
                &paired.key_id,
                counter,
                aad.as_bytes(),
                &serde_json::to_vec(&plain).unwrap(),
            )
            .unwrap(),
        )
        .unwrap()
    }
}

#[tokio::test]
async fn secure_lan_pairing_and_message_flow_accepts_encrypted_sms() {
    let temp = tempfile::tempdir().unwrap();
    let state = SmsPusherAppState::new_for_data_dir_with_bonjour(temp.path(), false).unwrap();
    state
        .update_settings(AppSettingsUpdate {
            preferred_port: Some(0),
            history_limit: Some(10),
            lan_enabled: Some(true),
            notifications_enabled: Some(true),
            network_interface_id: None,
            language_preference: None,
        })
        .unwrap();

    let transport = state.start_lan_server().await.unwrap();
    let port = transport.lan_port.unwrap();
    assert_eq!(transport.status, "running");
    assert_ne!(port, 0);
    state.drain_events();

    let client = reqwest::Client::new();
    let status = state.get_status().unwrap();
    let pairing_code = status.pairing_code.value.clone();
    let pairing_session_id = status.pairing_code.session_id.clone();
    let pairing_expires_at = status.pairing_code.expires_at.to_rfc3339();
    let mut secure_client = TestSecureClient::new(
        "android-client-1",
        "Test Android Device",
        &status.service_name,
        &pairing_session_id,
        &pairing_expires_at,
    );

    let start_response = post_json(
        &client,
        port,
        "/pair/v2/start",
        None,
        secure_client.start_body(&pairing_code),
    )
    .await;
    assert_eq!(start_response.status, reqwest::StatusCode::OK);

    let finish_body = secure_client.finish_body(&start_response.body);
    let finish_response = post_json(&client, port, "/pair/v2/finish", None, finish_body).await;
    assert_eq!(finish_response.status, reqwest::StatusCode::OK);
    let secure_message = secure_client.message_body(&finish_response.body, 1, "msg_1");
    let message_response = post_json(&client, port, "/secure/messages", None, secure_message).await;
    assert_eq!(message_response.status, reqwest::StatusCode::OK);
    assert_eq!(message_response.body, r#"{"status":"accepted"}"#);

    let devices = state.list_devices().unwrap();
    assert_eq!(devices.len(), 1);
    assert_eq!(devices[0].device_name, "Test Android Device");
    assert!(!devices[0].revoked);
    assert_eq!(devices[0].secure_transport_version, Some(2));

    let messages = state.list_messages().unwrap();
    assert_eq!(messages.len(), 1);
    assert_eq!(messages[0].message_id, "msg_1");
    assert_eq!(messages[0].sender, "TEST-SENDER");
    assert_eq!(messages[0].verification_code.as_deref(), Some("135790"));

    let events = state.drain_events();
    let message_events = events
        .iter()
        .filter(|event| matches!(event, ServiceEvent::MessageReceived { .. }))
        .count();
    let queue_events = events
        .iter()
        .filter(|event| matches!(event, ServiceEvent::QueueChanged { pending: 0 }))
        .count();
    let device_events = events
        .iter()
        .filter(|event| matches!(event, ServiceEvent::DeviceChanged { .. }))
        .count();
    assert_eq!(message_events, 1);
    assert_eq!(queue_events, 1);
    assert_eq!(device_events, 1);
    assert_eq!(state.retry_queue().pending, 0);

    state.stop_lan_server().await.unwrap();
}

#[tokio::test]
async fn v1_routes_require_secure_pairing_in_production() {
    let temp = tempfile::tempdir().unwrap();
    let state = SmsPusherAppState::new_for_data_dir_with_bonjour(temp.path(), false).unwrap();
    state
        .update_settings(AppSettingsUpdate {
            preferred_port: Some(0),
            history_limit: Some(10),
            lan_enabled: Some(true),
            notifications_enabled: Some(true),
            network_interface_id: None,
            language_preference: None,
        })
        .unwrap();

    let transport = state.start_lan_server().await.unwrap();
    let port = transport.lan_port.unwrap();
    let client = reqwest::Client::new();
    let pairing_code = state.get_status().unwrap().pairing_code.value;

    let pair_response = post_json(&client, port, "/pair", None, pair_body(&pairing_code)).await;
    assert_eq!(pair_response.status, reqwest::StatusCode::GONE);
    assert_eq!(pair_response.body, r#"{"error":"secure_pairing_required"}"#);

    state.stop_lan_server().await.unwrap();
}
