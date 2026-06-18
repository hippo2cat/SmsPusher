use smspusher_service::{ServiceEvent, ServiceEventSink, VecEventSink};

#[test]
fn drain_returns_events_once_in_emission_order() {
    let sink = VecEventSink::default();
    sink.emit(ServiceEvent::StatusChanged);
    sink.emit(ServiceEvent::QueueChanged { pending: 0 });

    assert_eq!(
        sink.drain(),
        vec![
            ServiceEvent::StatusChanged,
            ServiceEvent::QueueChanged { pending: 0 },
        ]
    );
    assert!(sink.drain().is_empty());
}
