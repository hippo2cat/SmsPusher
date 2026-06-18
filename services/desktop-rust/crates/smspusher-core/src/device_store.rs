use crate::models::StoredDevice;
use std::{collections::BTreeMap, fs, path::PathBuf};

pub trait DeviceStore {
    fn save(&mut self, device: StoredDevice);
    fn device(&self, device_id: &str) -> Option<StoredDevice>;
    fn device_by_client_instance_id(&self, client_instance_id: &str) -> Option<StoredDevice>;
    fn all_devices(&self) -> Vec<StoredDevice>;
    fn update(&mut self, device: StoredDevice);
    fn update_highest_counter(&mut self, device_id: &str, counter: u64);
    fn revoke(&mut self, device_id: &str);
}

#[derive(Default)]
pub struct InMemoryDeviceStore {
    devices: BTreeMap<String, StoredDevice>,
}

impl InMemoryDeviceStore {
    pub fn save(&mut self, device: StoredDevice) {
        <Self as DeviceStore>::save(self, device);
    }

    pub fn device(&self, device_id: &str) -> Option<StoredDevice> {
        <Self as DeviceStore>::device(self, device_id)
    }

    pub fn device_by_client_instance_id(&self, client_instance_id: &str) -> Option<StoredDevice> {
        <Self as DeviceStore>::device_by_client_instance_id(self, client_instance_id)
    }

    pub fn all_devices(&self) -> Vec<StoredDevice> {
        <Self as DeviceStore>::all_devices(self)
    }

    pub fn update(&mut self, device: StoredDevice) {
        <Self as DeviceStore>::update(self, device);
    }

    pub fn update_highest_counter(&mut self, device_id: &str, counter: u64) {
        <Self as DeviceStore>::update_highest_counter(self, device_id, counter);
    }

    pub fn revoke(&mut self, device_id: &str) {
        <Self as DeviceStore>::revoke(self, device_id);
    }
}

impl DeviceStore for InMemoryDeviceStore {
    fn save(&mut self, device: StoredDevice) {
        self.devices.insert(device.device_id.clone(), device);
    }

    fn device(&self, device_id: &str) -> Option<StoredDevice> {
        self.devices.get(device_id).cloned()
    }

    fn device_by_client_instance_id(&self, client_instance_id: &str) -> Option<StoredDevice> {
        self.devices
            .values()
            .find(|device| device.client_instance_id.as_deref() == Some(client_instance_id))
            .cloned()
    }

    fn all_devices(&self) -> Vec<StoredDevice> {
        let mut devices: Vec<_> = self.devices.values().cloned().collect();
        devices.sort_by(|a, b| a.device_name.cmp(&b.device_name));
        devices
    }

    fn update(&mut self, device: StoredDevice) {
        self.devices.insert(device.device_id.clone(), device);
    }

    fn update_highest_counter(&mut self, device_id: &str, counter: u64) {
        if let Some(device) = self.devices.get_mut(device_id) {
            device.highest_counter = counter;
        }
    }

    fn revoke(&mut self, device_id: &str) {
        if let Some(device) = self.devices.get_mut(device_id) {
            device.revoked = true;
        }
    }
}

pub struct FileDeviceStore {
    path: PathBuf,
    devices: BTreeMap<String, StoredDevice>,
    last_persistence_error: Option<String>,
}

impl FileDeviceStore {
    pub fn new(path: PathBuf) -> Self {
        let devices = fs::read(&path)
            .ok()
            .and_then(|data| serde_json::from_slice(&data).ok())
            .unwrap_or_default();
        Self {
            path,
            devices,
            last_persistence_error: None,
        }
    }

    pub fn last_persistence_error(&self) -> Option<&str> {
        self.last_persistence_error.as_deref()
    }

    pub fn save(&mut self, device: StoredDevice) {
        <Self as DeviceStore>::save(self, device);
    }

    pub fn device(&self, device_id: &str) -> Option<StoredDevice> {
        <Self as DeviceStore>::device(self, device_id)
    }

    pub fn device_by_client_instance_id(&self, client_instance_id: &str) -> Option<StoredDevice> {
        <Self as DeviceStore>::device_by_client_instance_id(self, client_instance_id)
    }

    pub fn all_devices(&self) -> Vec<StoredDevice> {
        <Self as DeviceStore>::all_devices(self)
    }

    pub fn update(&mut self, device: StoredDevice) {
        <Self as DeviceStore>::update(self, device);
    }

    pub fn update_highest_counter(&mut self, device_id: &str, counter: u64) {
        <Self as DeviceStore>::update_highest_counter(self, device_id, counter);
    }

    pub fn revoke(&mut self, device_id: &str) {
        <Self as DeviceStore>::revoke(self, device_id);
    }

    fn persist(&mut self) {
        let result = self.persist_result();
        self.last_persistence_error = result.err();
    }

    fn persist_result(&self) -> Result<(), String> {
        if let Some(parent) = self.path.parent() {
            fs::create_dir_all(parent).map_err(|error| error.to_string())?;
        }
        let data = serde_json::to_vec(&self.devices).map_err(|error| error.to_string())?;
        fs::write(&self.path, data).map_err(|error| error.to_string())
    }
}

impl DeviceStore for FileDeviceStore {
    fn save(&mut self, device: StoredDevice) {
        self.devices.insert(device.device_id.clone(), device);
        self.persist();
    }

    fn device(&self, device_id: &str) -> Option<StoredDevice> {
        self.devices.get(device_id).cloned()
    }

    fn device_by_client_instance_id(&self, client_instance_id: &str) -> Option<StoredDevice> {
        self.devices
            .values()
            .find(|device| device.client_instance_id.as_deref() == Some(client_instance_id))
            .cloned()
    }

    fn all_devices(&self) -> Vec<StoredDevice> {
        let mut devices: Vec<_> = self.devices.values().cloned().collect();
        devices.sort_by(|a, b| a.device_name.cmp(&b.device_name));
        devices
    }

    fn update(&mut self, device: StoredDevice) {
        self.devices.insert(device.device_id.clone(), device);
        self.persist();
    }

    fn update_highest_counter(&mut self, device_id: &str, counter: u64) {
        if let Some(device) = self.devices.get_mut(device_id) {
            device.highest_counter = counter;
            self.persist();
        }
    }

    fn revoke(&mut self, device_id: &str) {
        if let Some(device) = self.devices.get_mut(device_id) {
            device.revoked = true;
            self.persist();
        }
    }
}
