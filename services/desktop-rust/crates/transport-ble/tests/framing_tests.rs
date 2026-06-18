use serde_json::Value;
use transport_ble::{
    chunk_payload, decode_ack, decode_frame, encode_ack, encode_frame, BleFrame, BleFrameError,
    BleReassembler, BLE_FRAME_VERSION, SMSPUSHER_ACK_CHARACTERISTIC_UUID,
    SMSPUSHER_MESSAGE_CHARACTERISTIC_UUID, SMSPUSHER_METADATA_CHARACTERISTIC_UUID,
    SMSPUSHER_SERVICE_UUID,
};

#[test]
fn chunks_round_trip_when_frames_arrive_out_of_order() {
    let payload = b"{\"messageId\":\"msg_1\",\"body\":\"Your test verification code is 135790\"}";
    let frames = chunk_payload("ble-msg-1", payload, 12).unwrap();

    assert!(frames.len() > 1);
    assert!(frames
        .iter()
        .all(|frame| frame.version == BLE_FRAME_VERSION));
    assert!(frames.iter().all(|frame| frame.message_id == "ble-msg-1"));

    let mut reassembler = BleReassembler::new();
    assert!(reassembler.push(frames[1].clone()).unwrap().is_none());
    assert!(reassembler.push(frames[0].clone()).unwrap().is_none());
    let mut result = None;
    for frame in frames.iter().skip(2).cloned() {
        result = reassembler.push(frame).unwrap();
    }

    assert_eq!(result.unwrap(), payload);
}

#[test]
fn duplicate_chunk_is_rejected() {
    let frames = chunk_payload("ble-msg-2", b"hello world", 5).unwrap();
    let mut reassembler = BleReassembler::new();

    assert!(reassembler.push(frames[0].clone()).unwrap().is_none());
    let error = reassembler.push(frames[0].clone()).unwrap_err();

    assert_eq!(error, BleFrameError::DuplicateChunk);
}

#[test]
fn inconsistent_chunk_count_is_rejected() {
    let frames = chunk_payload("ble-msg-3", b"hello world", 5).unwrap();
    let mut reassembler = BleReassembler::new();
    let mut inconsistent = frames[1].clone();
    inconsistent.chunk_count += 1;

    assert!(reassembler.push(frames[0].clone()).unwrap().is_none());
    let error = reassembler.push(inconsistent).unwrap_err();

    assert_eq!(error, BleFrameError::InconsistentFrame);
}

#[test]
fn chunk_payload_rejects_zero_max_payload_size() {
    let error = chunk_payload("ble-msg-4", b"hello", 0).unwrap_err();

    assert_eq!(error, BleFrameError::InvalidMaxPayloadSize);
}

#[test]
fn frame_json_uses_camel_case_fields_and_round_trips() {
    let frame = BleFrame {
        version: BLE_FRAME_VERSION,
        message_id: "ble-msg-5".into(),
        chunk_index: 1,
        chunk_count: 2,
        payload: b"hello".to_vec(),
    };

    let encoded = encode_frame(&frame).unwrap();
    let json: Value = serde_json::from_slice(&encoded).unwrap();

    assert_eq!(json["version"], BLE_FRAME_VERSION);
    assert_eq!(json["messageId"], "ble-msg-5");
    assert_eq!(json["chunkIndex"], 1);
    assert_eq!(json["chunkCount"], 2);
    assert_eq!(json["payloadBase64"], "aGVsbG8=");
    assert_eq!(decode_frame(&encoded).unwrap(), frame);
}

#[test]
fn shared_gatt_uuids_match_android_peripheral_contract() {
    assert_eq!(
        SMSPUSHER_SERVICE_UUID,
        "2f89b10a-9f3d-4f2e-9f7a-3d3b7b5f0a11"
    );
    assert_eq!(
        SMSPUSHER_MESSAGE_CHARACTERISTIC_UUID,
        "2f89b10b-9f3d-4f2e-9f7a-3d3b7b5f0a11"
    );
    assert_eq!(
        SMSPUSHER_ACK_CHARACTERISTIC_UUID,
        "2f89b10c-9f3d-4f2e-9f7a-3d3b7b5f0a11"
    );
    assert_eq!(
        SMSPUSHER_METADATA_CHARACTERISTIC_UUID,
        "2f89b10d-9f3d-4f2e-9f7a-3d3b7b5f0a11"
    );
}

#[test]
fn ack_json_uses_version_and_message_id_and_round_trips() {
    let encoded = encode_ack("msg_1").unwrap();
    let json: Value = serde_json::from_slice(&encoded).unwrap();

    assert_eq!(json["version"], BLE_FRAME_VERSION);
    assert_eq!(json["messageId"], "msg_1");
    assert_eq!(decode_ack(&encoded).unwrap(), "msg_1");
}

#[test]
fn ack_decode_rejects_unsupported_version() {
    let error = decode_ack(br#"{"version":2,"messageId":"msg_1"}"#).unwrap_err();

    assert_eq!(error, BleFrameError::InconsistentFrame);
}

#[test]
fn ack_decode_rejects_empty_message_id() {
    let error = decode_ack(br#"{"version":1,"messageId":""}"#).unwrap_err();

    assert_eq!(error, BleFrameError::InconsistentFrame);
}
