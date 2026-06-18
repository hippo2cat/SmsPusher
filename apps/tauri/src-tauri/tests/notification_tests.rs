use chrono::Utc;
use smspusher_service::MessageSnapshot;
use smspusher_service::ServiceEvent;
use smspusher_tauri_lib::i18n::{DesktopI18n, LanguagePreference};
use smspusher_tauri_lib::notifications::{
    copy_action_request_for_message, handle_notification_action, notification_action_outcome,
    ClipboardWriter, NotificationActionError, NotificationActionRequest, NotificationPayload,
    COPY_CODE_ACTION,
};
use smspusher_tauri_lib::platform_notifications::{
    system_notification_action_metadata, VERIFICATION_CODE_CATEGORY_IDENTIFIER,
};

fn message_with_code() -> MessageSnapshot {
    MessageSnapshot {
        message_id: "msg-1".into(),
        sender: "Bank".into(),
        body: "Your test verification code is 135790".into(),
        received_at: Utc::now(),
        subscription_id: 1,
        device_id: "dev-1".into(),
        verification_code: Some("123456".into()),
    }
}

fn message_without_code() -> MessageSnapshot {
    MessageSnapshot {
        verification_code: None,
        body: "Your payment settled".into(),
        ..message_with_code()
    }
}

#[derive(Default)]
struct FakeClipboard {
    writes: Vec<String>,
}

impl ClipboardWriter for FakeClipboard {
    fn write_text(&mut self, value: &str) -> Result<(), NotificationActionError> {
        self.writes.push(value.to_owned());
        Ok(())
    }
}

#[test]
fn notification_payload_includes_sender_and_verification_code() {
    let message = message_with_code();

    let payload = NotificationPayload::from_message(&message);

    assert_eq!(payload.title, "Bank");
    assert_eq!(payload.body, "Your test verification code is 135790");
    assert_eq!(payload.verification_code.as_deref(), Some("123456"));
    assert_eq!(payload.message_id, "msg-1");
}

#[test]
fn code_bearing_message_creates_copy_action_request() {
    let request = copy_action_request_for_message(&message_with_code()).unwrap();

    assert_eq!(request.action, COPY_CODE_ACTION);
    assert_eq!(request.message_id, "msg-1");
    assert!(copy_action_request_for_message(&message_without_code()).is_none());
}

#[test]
fn copy_code_notification_action_uses_resolved_locale_label() {
    let english = DesktopI18n::for_locale("en-US");
    let chinese = DesktopI18n::for_locale("zh-CN");
    let macos_bridge = include_str!("../src/macos_user_notifications.m");

    assert_eq!(
        english.text("desktop.notification.copyCode", &[]),
        "Copy verification code"
    );
    assert_eq!(
        chinese.text("desktop.notification.copyCode", &[]),
        "复制验证码"
    );
    assert!(macos_bridge.contains("action_label"));
    assert!(!macos_bridge.contains("title:@\"复制验证码\""));
}

#[test]
fn desktop_i18n_resolves_auto_from_system_locale_candidates() {
    assert_eq!(
        DesktopI18n::resolve(LanguagePreference::Auto, ["zh-Hans-CN"]).locale(),
        "zh-CN"
    );
    assert_eq!(
        DesktopI18n::resolve(LanguagePreference::Auto, ["fr-FR"]).locale(),
        "en-US"
    );
}

#[test]
fn system_notification_metadata_enables_copy_action_for_verification_codes() {
    let with_code = NotificationPayload::from_message(&message_with_code());
    let without_code = NotificationPayload::from_message(&message_without_code());

    let metadata = system_notification_action_metadata(&with_code);

    assert_eq!(
        metadata.category_identifier,
        Some(VERIFICATION_CODE_CATEGORY_IDENTIFIER)
    );
    assert_eq!(
        metadata.action_identifier.as_deref(),
        Some(COPY_CODE_ACTION)
    );
    assert_eq!(metadata.verification_code.as_deref(), Some("123456"));
    assert!(system_notification_action_metadata(&without_code)
        .category_identifier
        .is_none());
}

#[test]
fn notification_action_outcome_copies_only_extracted_code() {
    let messages = vec![message_with_code()];
    let request = NotificationActionRequest {
        action: COPY_CODE_ACTION.into(),
        message_id: "msg-1".into(),
    };
    let mut clipboard = FakeClipboard::default();

    let outcome = handle_notification_action(&request, &messages, &mut clipboard).unwrap();

    assert_eq!(clipboard.writes, vec!["123456"]);
    assert_eq!(outcome.copied_code, "123456");
    assert_eq!(
        outcome.event,
        ServiceEvent::NotificationAction {
            action: COPY_CODE_ACTION.into(),
            message_id: "msg-1".into()
        }
    );
}

#[test]
fn notification_action_rejects_unknown_action_and_missing_code() {
    let messages = vec![message_without_code()];

    let unknown = notification_action_outcome(
        &NotificationActionRequest {
            action: "archive_message".into(),
            message_id: "msg-1".into(),
        },
        &messages,
    )
    .unwrap_err();
    assert_eq!(
        unknown,
        NotificationActionError::UnknownAction("archive_message".into())
    );

    let missing_code = notification_action_outcome(
        &NotificationActionRequest {
            action: COPY_CODE_ACTION.into(),
            message_id: "msg-1".into(),
        },
        &messages,
    )
    .unwrap_err();
    assert_eq!(
        missing_code,
        NotificationActionError::MissingVerificationCode("msg-1".into())
    );

    let missing_message = notification_action_outcome(
        &NotificationActionRequest {
            action: COPY_CODE_ACTION.into(),
            message_id: "missing".into(),
        },
        &messages,
    )
    .unwrap_err();
    assert_eq!(
        missing_message,
        NotificationActionError::MessageNotFound("missing".into())
    );
}
