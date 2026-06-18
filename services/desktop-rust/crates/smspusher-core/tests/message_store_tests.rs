use chrono::{Duration, TimeZone, Utc};
use smspusher_core::{IncomingMessage, MessageStore, SqliteMessageStore};

fn message(id: &str, device_id: &str, seconds: i64) -> IncomingMessage {
    IncomingMessage {
        message_id: id.into(),
        sender: "TEST-SENDER".into(),
        body: format!("body {id}"),
        received_at: Utc.with_ymd_and_hms(2026, 6, 5, 8, 0, 0).unwrap()
            + Duration::seconds(seconds),
        subscription_id: 1,
        device_id: device_id.into(),
    }
}

#[test]
fn duplicate_device_message_id_is_inserted_once() {
    let mut store = SqliteMessageStore::open_in_memory(1000).unwrap();
    let first = message("msg_1", "dev_1", 1);

    assert!(store.insert_if_new(&first).unwrap());
    assert!(!store.insert_if_new(&first).unwrap());
    assert_eq!(store.latest(10).unwrap().len(), 1);
}

#[test]
fn same_message_id_from_different_devices_is_kept() {
    let mut store = SqliteMessageStore::open_in_memory(1000).unwrap();

    assert!(store.insert_if_new(&message("msg_1", "dev_1", 1)).unwrap());
    assert!(store.insert_if_new(&message("msg_1", "dev_2", 2)).unwrap());
    assert_eq!(store.latest(10).unwrap().len(), 2);
}

#[test]
fn retention_keeps_latest_messages() {
    let mut store = SqliteMessageStore::open_in_memory(2).unwrap();

    assert!(store.insert_if_new(&message("old", "dev_1", 1)).unwrap());
    assert!(store.insert_if_new(&message("middle", "dev_1", 2)).unwrap());
    assert!(store.insert_if_new(&message("new", "dev_1", 3)).unwrap());

    let ids: Vec<_> = store
        .latest(10)
        .unwrap()
        .into_iter()
        .map(|message| message.message_id)
        .collect();
    assert_eq!(ids, vec!["new", "middle"]);
}
