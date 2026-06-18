use serde::Serialize;
use std::sync::{Arc, Mutex};

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ServiceEvent {
    StatusChanged,
    PairingCodeChanged {
        value: String,
    },
    DeviceChanged {
        device_id: String,
    },
    MessageReceived {
        message_id: String,
        device_id: String,
    },
    QueueChanged {
        pending: usize,
    },
    TransportChanged {
        status: String,
    },
    NotificationAction {
        action: String,
        message_id: String,
    },
}

pub trait ServiceEventSink: Clone + Send + Sync + 'static {
    fn emit(&self, event: ServiceEvent);
}

#[derive(Clone, Default)]
pub struct VecEventSink {
    events: Arc<Mutex<Vec<ServiceEvent>>>,
}

impl VecEventSink {
    pub fn events(&self) -> Vec<ServiceEvent> {
        self.events.lock().expect("event sink lock").clone()
    }

    pub fn drain(&self) -> Vec<ServiceEvent> {
        self.events
            .lock()
            .expect("event sink lock")
            .drain(..)
            .collect()
    }
}

impl ServiceEventSink for VecEventSink {
    fn emit(&self, event: ServiceEvent) {
        self.events.lock().expect("event sink lock").push(event);
    }
}
