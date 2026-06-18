use chrono::{DateTime, Duration, Utc};
use rand::{rngs::StdRng, RngCore, SeedableRng};
use serde::Serialize;

pub const PAIRING_CODE_LIFETIME_SECONDS: i64 = 30;

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct PairingCode {
    pub value: String,
    pub session_id: String,
    pub expires_at: DateTime<Utc>,
}

impl PairingCode {
    pub fn is_valid(&self, now: DateTime<Utc>) -> bool {
        now < self.expires_at
    }
}

pub struct TokenGenerator {
    rng: StdRng,
}

impl Default for TokenGenerator {
    fn default() -> Self {
        Self::new()
    }
}

impl TokenGenerator {
    pub fn new() -> Self {
        Self {
            rng: StdRng::from_entropy(),
        }
    }

    pub fn seeded(seed: u64) -> Self {
        Self {
            rng: StdRng::seed_from_u64(seed),
        }
    }

    pub fn make_pairing_code(&mut self, now: DateTime<Utc>) -> PairingCode {
        let value = format!("{:06}", self.rng.next_u32() % 1_000_000);
        let mut session_bytes = [0u8; 16];
        self.rng.fill_bytes(&mut session_bytes);
        PairingCode {
            value,
            session_id: hex::encode(session_bytes),
            expires_at: now + Duration::seconds(PAIRING_CODE_LIFETIME_SECONDS),
        }
    }

    pub fn make_opaque_token(&mut self) -> String {
        let mut bytes = [0u8; 32];
        self.rng.fill_bytes(&mut bytes);
        hex::encode(bytes)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::TimeZone;

    #[test]
    fn pairing_code_expires_after_thirty_seconds() {
        let now = Utc.with_ymd_and_hms(2026, 6, 15, 8, 0, 0).unwrap();
        let mut generator = TokenGenerator::seeded(7);
        let code = generator.make_pairing_code(now);

        assert!(code.is_valid(now + Duration::seconds(29)));
        assert!(!code.is_valid(now + Duration::seconds(30)));
    }
}
