use chrono::Utc;
use smspusher_service::{MessageSnapshot, ServiceEvent};
use smspusher_tauri_lib::{
    commands::event_name,
    event_pump::{
        deliver_notification, dispatch_plan, notification_delivery_plan, NotificationBackend,
    },
    notifications::COPY_CODE_ACTION,
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
        message_id: "msg-2".into(),
        body: "Your payment settled".into(),
        verification_code: None,
        ..message_with_code()
    }
}

#[derive(Default)]
struct FakeNotificationBackend {
    shown: std::cell::RefCell<Vec<String>>,
}

impl NotificationBackend for FakeNotificationBackend {
    fn show(&self, payload: &smspusher_tauri_lib::notifications::NotificationPayload) {
        self.shown.borrow_mut().push(payload.message_id.clone());
    }
}

#[test]
fn dispatch_plan_maps_message_event_to_tauri_event_and_action_notification() {
    let event = ServiceEvent::MessageReceived {
        message_id: "msg-1".into(),
        device_id: "dev-1".into(),
    };
    let messages = vec![message_with_code()];

    let planned = dispatch_plan(vec![event.clone()], &messages, true);

    assert_eq!(event_name(&event), "message_received");
    assert_eq!(planned.len(), 1);
    assert_eq!(planned[0].event_name, "message_received");
    assert_eq!(planned[0].event, event);
    let notification = planned[0].notification.as_ref().unwrap();
    assert_eq!(notification.payload.message_id, "msg-1");
    assert_eq!(notification.payload.title, "Bank");
    assert_eq!(notification.payload.body, "Your test verification code is 135790");
    assert_eq!(
        notification.payload.verification_code.as_deref(),
        Some("123456")
    );
    let action = notification.action_request.as_ref().unwrap();
    assert_eq!(action.action, COPY_CODE_ACTION);
    assert_eq!(action.message_id, "msg-1");
}

#[test]
fn deliver_notification_sends_code_messages_through_runtime_backend() {
    let dispatch = dispatch_plan(
        vec![ServiceEvent::MessageReceived {
            message_id: "msg-1".into(),
            device_id: "dev-1".into(),
        }],
        &[message_with_code()],
        true,
    )
    .remove(0)
    .notification
    .unwrap();
    let backend = FakeNotificationBackend::default();

    let delivery = deliver_notification(&backend, dispatch);

    assert_eq!(delivery.basic_popup.message_id, "msg-1");
    assert_eq!(backend.shown.borrow().as_slice(), ["msg-1"]);
}

#[test]
fn notification_delivery_plan_keeps_basic_popup_for_code_messages() {
    let dispatch = dispatch_plan(
        vec![ServiceEvent::MessageReceived {
            message_id: "msg-1".into(),
            device_id: "dev-1".into(),
        }],
        &[message_with_code()],
        true,
    )
    .remove(0)
    .notification
    .unwrap();

    let delivery = notification_delivery_plan(dispatch.clone());

    assert_eq!(delivery.basic_popup, dispatch.payload);
    let action = delivery.action_request.as_ref().unwrap();
    assert_eq!(action.action, COPY_CODE_ACTION);
    assert_eq!(action.message_id, "msg-1");
}

#[test]
fn dispatch_plan_keeps_basic_notification_for_message_without_code() {
    let event = ServiceEvent::MessageReceived {
        message_id: "msg-2".into(),
        device_id: "dev-1".into(),
    };
    let messages = vec![message_without_code()];

    let planned = dispatch_plan(vec![event], &messages, true);

    let notification = planned[0].notification.as_ref().unwrap();
    assert_eq!(notification.payload.message_id, "msg-2");
    assert!(notification.action_request.is_none());
}

#[test]
fn dispatch_plan_skips_notifications_when_disabled() {
    let event = ServiceEvent::MessageReceived {
        message_id: "msg-1".into(),
        device_id: "dev-1".into(),
    };
    let messages = vec![message_with_code()];

    let planned = dispatch_plan(vec![event], &messages, false);

    assert!(planned[0].notification.is_none());
}
