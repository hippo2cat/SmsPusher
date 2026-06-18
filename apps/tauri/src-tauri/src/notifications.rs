use serde::Serialize;
use smspusher_service::{MessageSnapshot, ServiceEvent};

pub const COPY_CODE_ACTION: &str = "copy_verification_code";

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct NotificationPayload {
    pub message_id: String,
    pub title: String,
    pub body: String,
    pub verification_code: Option<String>,
}

impl NotificationPayload {
    pub fn from_message(message: &MessageSnapshot) -> Self {
        Self {
            message_id: message.message_id.clone(),
            title: message.sender.clone(),
            body: message.body.clone(),
            verification_code: message.verification_code.clone(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct NotificationActionRequest {
    pub action: String,
    pub message_id: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NotificationDispatch {
    pub payload: NotificationPayload,
    pub action_request: Option<NotificationActionRequest>,
}

impl NotificationDispatch {
    pub fn from_message(message: &MessageSnapshot) -> Self {
        Self {
            payload: NotificationPayload::from_message(message),
            action_request: copy_action_request_for_message(message),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NotificationActionOutcome {
    pub event: ServiceEvent,
    pub copied_code: String,
}

#[derive(Debug, thiserror::Error, Clone, PartialEq, Eq)]
pub enum NotificationActionError {
    #[error("unknown notification action: {0}")]
    UnknownAction(String),
    #[error("message not found for notification action: {0}")]
    MessageNotFound(String),
    #[error("message has no verification code: {0}")]
    MissingVerificationCode(String),
    #[error("clipboard error: {0}")]
    Clipboard(String),
}

pub trait ClipboardWriter {
    fn write_text(&mut self, value: &str) -> Result<(), NotificationActionError>;
}

pub struct SystemClipboard;

impl ClipboardWriter for SystemClipboard {
    fn write_text(&mut self, value: &str) -> Result<(), NotificationActionError> {
        let mut clipboard = arboard::Clipboard::new()
            .map_err(|error| NotificationActionError::Clipboard(error.to_string()))?;
        clipboard
            .set_text(value.to_owned())
            .map_err(|error| NotificationActionError::Clipboard(error.to_string()))
    }
}

pub fn copy_action_request_for_message(
    message: &MessageSnapshot,
) -> Option<NotificationActionRequest> {
    message
        .verification_code
        .as_ref()
        .map(|_| NotificationActionRequest {
            action: COPY_CODE_ACTION.into(),
            message_id: message.message_id.clone(),
        })
}

pub fn notification_action_outcome(
    request: &NotificationActionRequest,
    messages: &[MessageSnapshot],
) -> Result<NotificationActionOutcome, NotificationActionError> {
    if request.action != COPY_CODE_ACTION {
        return Err(NotificationActionError::UnknownAction(
            request.action.clone(),
        ));
    }
    let message = messages
        .iter()
        .find(|message| message.message_id == request.message_id)
        .ok_or_else(|| NotificationActionError::MessageNotFound(request.message_id.clone()))?;
    let copied_code = message.verification_code.clone().ok_or_else(|| {
        NotificationActionError::MissingVerificationCode(request.message_id.clone())
    })?;
    Ok(NotificationActionOutcome {
        event: ServiceEvent::NotificationAction {
            action: request.action.clone(),
            message_id: request.message_id.clone(),
        },
        copied_code,
    })
}

pub fn handle_notification_action<W: ClipboardWriter>(
    request: &NotificationActionRequest,
    messages: &[MessageSnapshot],
    clipboard: &mut W,
) -> Result<NotificationActionOutcome, NotificationActionError> {
    let outcome = notification_action_outcome(request, messages)?;
    clipboard.write_text(&outcome.copied_code)?;
    Ok(outcome)
}
