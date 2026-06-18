use crate::i18n::DesktopI18n;
use crate::notifications::{NotificationActionRequest, NotificationPayload, COPY_CODE_ACTION};
#[cfg(target_os = "macos")]
use std::ffi::{CStr, CString};

#[derive(Debug, thiserror::Error)]
pub enum PlatformNotificationError {
    #[error("platform notification actions are not supported on this OS")]
    Unsupported,
    #[error("native notification error: {0}")]
    Native(String),
}

pub const VERIFICATION_CODE_CATEGORY_IDENTIFIER: &str = "verification-code-message";
#[cfg(target_os = "macos")]
const COPY_CODE_FALLBACK_LABEL: &str = "Copy verification code";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SystemNotificationActionMetadata {
    pub category_identifier: Option<&'static str>,
    pub action_identifier: Option<String>,
    pub verification_code: Option<String>,
}

pub fn system_notification_action_metadata(
    payload: &NotificationPayload,
) -> SystemNotificationActionMetadata {
    match payload.verification_code.as_ref() {
        Some(code) if !code.is_empty() => SystemNotificationActionMetadata {
            category_identifier: Some(VERIFICATION_CODE_CATEGORY_IDENTIFIER),
            action_identifier: Some(COPY_CODE_ACTION.into()),
            verification_code: Some(code.clone()),
        },
        _ => SystemNotificationActionMetadata {
            category_identifier: None,
            action_identifier: None,
            verification_code: None,
        },
    }
}

#[cfg(target_os = "macos")]
extern "C" {
    fn smspusher_request_user_notification_authorization();
    fn smspusher_show_user_notification(
        identifier: *const std::os::raw::c_char,
        title: *const std::os::raw::c_char,
        body: *const std::os::raw::c_char,
        subtitle: *const std::os::raw::c_char,
        category_identifier: *const std::os::raw::c_char,
        action_identifier: *const std::os::raw::c_char,
        action_label: *const std::os::raw::c_char,
        verification_code: *const std::os::raw::c_char,
        error: *mut std::os::raw::c_char,
        error_length: usize,
    ) -> bool;
}

pub fn request_system_notification_authorization() {
    #[cfg(target_os = "macos")]
    unsafe {
        smspusher_request_user_notification_authorization();
    }
}

#[cfg(target_os = "macos")]
pub fn show_system_notification(
    payload: &NotificationPayload,
    i18n: &DesktopI18n,
) -> Result<(), PlatformNotificationError> {
    let identifier = c_string(format!("smspusher-{}", payload.message_id));
    let title = c_string(&payload.title);
    let body = c_string(&payload.body);
    let metadata = system_notification_action_metadata(payload);
    let subtitle = c_string(
        metadata
            .verification_code
            .as_ref()
            .map(|code| i18n.text("desktop.notification.codeSubtitle", &[("code", code)]))
            .unwrap_or_default(),
    );
    let category_identifier = c_string(metadata.category_identifier.unwrap_or_default());
    let action_identifier = c_string(metadata.action_identifier.as_deref().unwrap_or_default());
    let action_label = c_string(i18n.text("desktop.notification.copyCode", &[]));
    let verification_code = c_string(metadata.verification_code.as_deref().unwrap_or_default());
    let mut error = vec![0 as std::os::raw::c_char; 512];
    let delivered = unsafe {
        smspusher_show_user_notification(
            identifier.as_ptr(),
            title.as_ptr(),
            body.as_ptr(),
            subtitle.as_ptr(),
            category_identifier.as_ptr(),
            action_identifier.as_ptr(),
            action_label.as_ptr(),
            verification_code.as_ptr(),
            error.as_mut_ptr(),
            error.len(),
        )
    };
    if delivered {
        return Ok(());
    }
    let message = unsafe { CStr::from_ptr(error.as_ptr()) }
        .to_string_lossy()
        .into_owned();
    Err(PlatformNotificationError::Native(message))
}

#[cfg(not(target_os = "macos"))]
pub fn show_system_notification(
    _payload: &NotificationPayload,
    _i18n: &DesktopI18n,
) -> Result<(), PlatformNotificationError> {
    Err(PlatformNotificationError::Unsupported)
}

#[cfg(target_os = "macos")]
fn c_string(value: impl AsRef<str>) -> CString {
    let sanitized = value.as_ref().replace('\0', " ");
    CString::new(sanitized).expect("notification string was sanitized")
}

pub trait ActionNotificationBackend {
    fn show_with_action(
        &self,
        payload: &NotificationPayload,
        action: &NotificationActionRequest,
    ) -> Result<Option<NotificationActionRequest>, PlatformNotificationError>;
}

#[derive(Debug, Clone)]
pub struct NativeActionNotificationBackend {
    app_identifier: String,
}

impl NativeActionNotificationBackend {
    pub fn new(app_identifier: impl Into<String>) -> Self {
        Self {
            app_identifier: app_identifier.into(),
        }
    }
}

#[cfg(target_os = "macos")]
impl ActionNotificationBackend for NativeActionNotificationBackend {
    fn show_with_action(
        &self,
        payload: &NotificationPayload,
        action: &NotificationActionRequest,
    ) -> Result<Option<NotificationActionRequest>, PlatformNotificationError> {
        use mac_notification_sys::{MainButton, Notification, NotificationResponse};

        // `mac-notification-sys` allows setting the app only once; ignore later
        // attempts because the first successful bundle binding is sufficient.
        let _ = mac_notification_sys::set_application(&self.app_identifier);

        let mut notification = Notification::new();
        notification
            .title(&payload.title)
            .message(&payload.body)
            .main_button(MainButton::SingleAction(COPY_CODE_FALLBACK_LABEL))
            .wait_for_click(true)
            .asynchronous(false);

        match notification
            .send()
            .map_err(|error| PlatformNotificationError::Native(error.to_string()))?
        {
            NotificationResponse::ActionButton(label) if label == COPY_CODE_FALLBACK_LABEL => {
                Ok(Some(action.clone()))
            }
            _ => Ok(None),
        }
    }
}

#[cfg(not(target_os = "macos"))]
impl ActionNotificationBackend for NativeActionNotificationBackend {
    fn show_with_action(
        &self,
        _payload: &NotificationPayload,
        _action: &NotificationActionRequest,
    ) -> Result<Option<NotificationActionRequest>, PlatformNotificationError> {
        let _ = &self.app_identifier;
        Err(PlatformNotificationError::Unsupported)
    }
}
