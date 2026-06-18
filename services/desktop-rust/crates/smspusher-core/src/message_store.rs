use crate::models::IncomingMessage;
use chrono::{TimeZone, Utc};
use rusqlite::{params, Connection};
use std::path::Path;
use thiserror::Error;

#[derive(Debug, Error)]
pub enum MessageStoreError {
    #[error("sqlite error: {0}")]
    Sqlite(#[from] rusqlite::Error),
}

pub trait MessageStore {
    fn insert_if_new(&mut self, message: &IncomingMessage) -> Result<bool, MessageStoreError>;
    fn latest(&self, limit: usize) -> Result<Vec<IncomingMessage>, MessageStoreError>;
}

pub struct SqliteMessageStore {
    connection: Connection,
    retention_limit: usize,
}

impl SqliteMessageStore {
    pub fn open(path: impl AsRef<Path>, retention_limit: usize) -> Result<Self, MessageStoreError> {
        let connection = Connection::open(path)?;
        let store = Self {
            connection,
            retention_limit,
        };
        store.create_schema()?;
        Ok(store)
    }

    pub fn open_in_memory(retention_limit: usize) -> Result<Self, MessageStoreError> {
        let connection = Connection::open_in_memory()?;
        let store = Self {
            connection,
            retention_limit,
        };
        store.create_schema()?;
        Ok(store)
    }

    fn create_schema(&self) -> Result<(), MessageStoreError> {
        self.connection.execute_batch(
            r#"
            CREATE TABLE IF NOT EXISTS messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                message_id TEXT NOT NULL,
                sender TEXT NOT NULL,
                body TEXT NOT NULL,
                received_at REAL NOT NULL,
                subscription_id INTEGER NOT NULL,
                device_id TEXT NOT NULL,
                delivered_at REAL NOT NULL,
                UNIQUE(device_id, message_id)
            );
            "#,
        )?;
        Ok(())
    }

    fn enforce_retention(&self) -> Result<(), MessageStoreError> {
        self.connection.execute(
            r#"
            DELETE FROM messages
            WHERE id NOT IN (
                SELECT id FROM messages
                ORDER BY received_at DESC, id DESC
                LIMIT ?
            );
            "#,
            params![self.retention_limit as i64],
        )?;
        Ok(())
    }
}

impl MessageStore for SqliteMessageStore {
    fn insert_if_new(&mut self, message: &IncomingMessage) -> Result<bool, MessageStoreError> {
        self.connection.execute(
            r#"
            INSERT OR IGNORE INTO messages
                (message_id, sender, body, received_at, subscription_id, device_id, delivered_at)
            VALUES (?, ?, ?, ?, ?, ?, ?);
            "#,
            params![
                message.message_id,
                message.sender,
                message.body,
                message.received_at.timestamp_millis() as f64 / 1000.0,
                message.subscription_id,
                message.device_id,
                Utc::now().timestamp_millis() as f64 / 1000.0,
            ],
        )?;
        let inserted = self.connection.changes() == 1;
        if inserted {
            self.enforce_retention()?;
        }
        Ok(inserted)
    }

    fn latest(&self, limit: usize) -> Result<Vec<IncomingMessage>, MessageStoreError> {
        let mut statement = self.connection.prepare(
            r#"
            SELECT message_id, sender, body, received_at, subscription_id, device_id
            FROM messages
            ORDER BY received_at DESC, id DESC
            LIMIT ?;
            "#,
        )?;
        let rows = statement.query_map(params![limit as i64], |row| {
            let received_at: f64 = row.get(3)?;
            Ok(IncomingMessage {
                message_id: row.get(0)?,
                sender: row.get(1)?,
                body: row.get(2)?,
                received_at: date_from_seconds(received_at),
                subscription_id: row.get(4)?,
                device_id: row.get(5)?,
            })
        })?;

        let mut messages = Vec::new();
        for row in rows {
            messages.push(row?);
        }
        Ok(messages)
    }
}

fn date_from_seconds(value: f64) -> chrono::DateTime<Utc> {
    let seconds = value.trunc() as i64;
    let nanos = ((value.fract()) * 1_000_000_000.0).round() as u32;
    Utc.timestamp_opt(seconds, nanos)
        .single()
        .unwrap_or_else(|| Utc.timestamp_opt(seconds, 0).unwrap())
}
