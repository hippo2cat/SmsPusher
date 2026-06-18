use chrono::{TimeZone, Utc};
use smspusher_core::{
    json, AuthCheckRequest, IncomingMessage, PairRequest, PairResponse, RefreshRequest,
    RefreshResponse,
};

#[test]
fn pair_request_uses_android_field_names() {
    let payload = r#"{"pairingCode":"123456","deviceName":"Test Android Device","clientVersion":1,"clientInstanceId":"android-1"}"#;

    let request: PairRequest = json::from_slice(payload.as_bytes()).unwrap();

    assert_eq!(request.pairing_code, "123456");
    assert_eq!(request.device_name, "Test Android Device");
    assert_eq!(request.client_version, 1);
    assert_eq!(request.client_instance_id.as_deref(), Some("android-1"));
}

#[test]
fn pair_response_serializes_iso8601_dates() {
    let response = PairResponse {
        device_id: "dev_4f8b2a7c".into(),
        access_token: "access".into(),
        access_token_expires_at: Utc.with_ymd_and_hms(2026, 6, 6, 8, 0, 0).unwrap(),
        refresh_token: "refresh".into(),
        refresh_token_expires_at: Utc.with_ymd_and_hms(2026, 9, 3, 8, 0, 0).unwrap(),
    };

    let json = json::to_string(&response).unwrap();

    assert_eq!(
        json,
        r#"{"deviceId":"dev_4f8b2a7c","accessToken":"access","accessTokenExpiresAt":"2026-06-06T08:00:00Z","refreshToken":"refresh","refreshTokenExpiresAt":"2026-09-03T08:00:00Z"}"#
    );
}

#[test]
fn incoming_message_decodes_android_payload() {
    let payload = r#"{"messageId":"msg_1","sender":"TEST-SENDER","body":"测试验证码 135790","receivedAt":"2026-06-05T08:05:12Z","subscriptionId":1,"deviceId":"dev_1"}"#;

    let message: IncomingMessage = json::from_slice(payload.as_bytes()).unwrap();

    assert_eq!(message.message_id, "msg_1");
    assert_eq!(message.sender, "TEST-SENDER");
    assert_eq!(message.body, "测试验证码 135790");
    assert_eq!(message.subscription_id, 1);
    assert_eq!(message.device_id, "dev_1");
    assert_eq!(
        message.received_at,
        Utc.with_ymd_and_hms(2026, 6, 5, 8, 5, 12).unwrap()
    );
}

#[test]
fn refresh_and_auth_check_requests_match_existing_protocol() {
    let refresh: RefreshRequest =
        json::from_slice(br#"{"deviceId":"dev_1","refreshToken":"refresh"}"#).unwrap();
    let check: AuthCheckRequest = json::from_slice(br#"{"deviceId":"dev_1"}"#).unwrap();

    assert_eq!(refresh.device_id, "dev_1");
    assert_eq!(refresh.refresh_token, "refresh");
    assert_eq!(check.device_id, "dev_1");

    let refresh_response = RefreshResponse {
        access_token: "new-access".into(),
        access_token_expires_at: Utc.with_ymd_and_hms(2026, 6, 7, 8, 0, 0).unwrap(),
        refresh_token: "new-refresh".into(),
        refresh_token_expires_at: Utc.with_ymd_and_hms(2026, 9, 4, 8, 0, 0).unwrap(),
    };

    assert_eq!(
        json::to_string(&refresh_response).unwrap(),
        r#"{"accessToken":"new-access","accessTokenExpiresAt":"2026-06-07T08:00:00Z","refreshToken":"new-refresh","refreshTokenExpiresAt":"2026-09-04T08:00:00Z"}"#
    );
}
