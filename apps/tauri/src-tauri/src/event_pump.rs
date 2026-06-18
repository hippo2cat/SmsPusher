use crate::{
    app_state::SmsPusherAppState,
    commands::event_name,
    notifications::{NotificationActionRequest, NotificationDispatch, NotificationPayload},
};
use smspusher_service::{MessageSnapshot, ServiceEvent};
use tauri::{AppHandle, Emitter, Manager};
#[cfg(not(target_os = "macos"))]
use tauri_plugin_notification::NotificationExt;
use tokio::time::{sleep, Duration};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PlannedDispatch {
    pub event_name: &'static str,
    pub event: ServiceEvent,
    pub notification: Option<NotificationDispatch>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NotificationDeliveryPlan {
    pub basic_popup: NotificationPayload,
    pub action_request: Option<NotificationActionRequest>,
}

pub trait NotificationBackend {
    fn show(&self, payload: &NotificationPayload);
}

struct RuntimeNotificationBackend {
    app: AppHandle,
}

impl RuntimeNotificationBackend {
    fn new(app: AppHandle) -> Self {
        Self { app }
    }
}

impl NotificationBackend for RuntimeNotificationBackend {
    fn show(&self, payload: &NotificationPayload) {
        show_basic_notification(&self.app, payload);
    }
}

pub fn dispatch_plan(
    events: Vec<ServiceEvent>,
    messages: &[MessageSnapshot],
    notifications_enabled: bool,
) -> Vec<PlannedDispatch> {
    events
        .into_iter()
        .map(|event| {
            let notification = if notifications_enabled {
                match &event {
                    ServiceEvent::MessageReceived { message_id, .. } => messages
                        .iter()
                        .find(|message| &message.message_id == message_id)
                        .map(NotificationDispatch::from_message),
                    _ => None,
                }
            } else {
                None
            };
            PlannedDispatch {
                event_name: event_name(&event),
                event,
                notification,
            }
        })
        .collect()
}

pub fn notification_delivery_plan(dispatch: NotificationDispatch) -> NotificationDeliveryPlan {
    NotificationDeliveryPlan {
        basic_popup: dispatch.payload,
        action_request: dispatch.action_request,
    }
}

pub fn deliver_notification<B: NotificationBackend>(
    backend: &B,
    notification: NotificationDispatch,
) -> NotificationDeliveryPlan {
    let delivery = notification_delivery_plan(notification);
    backend.show(&delivery.basic_popup);
    delivery
}

#[cfg(target_os = "macos")]
fn show_basic_notification(app: &AppHandle, payload: &NotificationPayload) {
    let state = app.state::<SmsPusherAppState>();
    let settings = state.settings();
    let i18n = crate::i18n::DesktopI18n::resolve(
        settings.language_preference,
        crate::i18n::current_system_locale_candidates(),
    );
    if let Err(error) = crate::platform_notifications::show_system_notification(payload, &i18n) {
        tracing::warn!(
            message_id = %payload.message_id,
            error = %error,
            "failed to show notification"
        );
    }
}

#[cfg(not(target_os = "macos"))]
fn show_basic_notification(app: &AppHandle, payload: &NotificationPayload) {
    if let Err(error) = app
        .notification()
        .builder()
        .title(payload.title.clone())
        .body(payload.body.clone())
        .show()
    {
        tracing::warn!(
            message_id = %payload.message_id,
            error = %error,
            "failed to show notification"
        );
    }
}

pub async fn run_event_pump(app: AppHandle) {
    loop {
        sleep(Duration::from_millis(250)).await;
        let state = app.state::<SmsPusherAppState>();
        let events = state.drain_events();
        if events.is_empty() {
            continue;
        }
        let messages = state.list_messages().unwrap_or_default();
        let notifications_enabled = state.settings().notifications_enabled;
        let notification_backend = RuntimeNotificationBackend::new(app.clone());
        for dispatch in dispatch_plan(events, &messages, notifications_enabled) {
            let event = dispatch.event;
            let name = dispatch.event_name;
            if let Err(error) = app.emit(name, event) {
                tracing::warn!(event = name, error = %error, "failed to emit service event");
            } else {
                tracing::debug!(event = name, "service event emitted");
            }
            if let Some(notification) = dispatch.notification {
                // Action metadata must not replace the reliable system popup.
                tracing::info!(
                    message_id = %notification.payload.message_id,
                    "delivering message notification"
                );
                let _ = deliver_notification(&notification_backend, notification);
            }
        }
    }
}
