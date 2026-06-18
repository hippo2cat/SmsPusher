#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PairingTranscript {
    bytes: Vec<u8>,
}

impl PairingTranscript {
    pub fn new(
        pairing_session_id: &str,
        desktop_service_instance_id: &str,
        desktop_device_name: &str,
        android_client_instance_id: &str,
        android_device_name: &str,
        desktop_base_url: &str,
        pairing_expires_at: &str,
    ) -> Self {
        let bytes = [
            "SmsPusher",
            crate::PROTOCOL_NAME,
            crate::PAKE_NAME,
            "desktopRole=server",
            "androidRole=client",
            pairing_session_id,
            desktop_service_instance_id,
            desktop_device_name,
            android_client_instance_id,
            android_device_name,
            desktop_base_url,
            pairing_expires_at,
        ]
        .join("\n")
        .into_bytes();
        Self { bytes }
    }

    pub fn client_name(&self) -> Vec<u8> {
        [self.bytes.as_slice(), b"\nrole=client"].concat()
    }

    pub fn server_name(&self) -> Vec<u8> {
        [self.bytes.as_slice(), b"\nrole=server"].concat()
    }

    pub fn as_bytes(&self) -> &[u8] {
        &self.bytes
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SecureAad {
    bytes: Vec<u8>,
}

impl SecureAad {
    pub fn new(method: &str, path: &str, device_id: &str, key_id: &str, counter: u64) -> Self {
        Self {
            bytes: [
                "SmsPusher".to_owned(),
                crate::PROTOCOL_NAME.to_owned(),
                method.to_owned(),
                path.to_owned(),
                device_id.to_owned(),
                key_id.to_owned(),
                counter.to_string(),
            ]
            .join("\n")
            .into_bytes(),
        }
    }

    pub fn as_bytes(&self) -> &[u8] {
        &self.bytes
    }
}
