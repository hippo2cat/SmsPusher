use chrono::{TimeZone, Utc};
use smspusher_core::{InMemoryDeviceStore, SqliteMessageStore, TokenGenerator};
use smspusher_service::{DesktopService, DesktopServiceConfig, VecEventSink};
use std::net::Ipv4Addr;

use transport_lan::{
    advertised_ipv4_for_interface, preferred_advertised_ipv4, BonjourTxtRecord,
    LanNetworkInterface, LanServer, LanServerConfig,
};

fn service() -> DesktopService<
    InMemoryDeviceStore,
    SqliteMessageStore,
    VecEventSink,
    Box<dyn Fn() -> chrono::DateTime<Utc> + Send + Sync>,
> {
    let now = Utc.with_ymd_and_hms(2026, 6, 5, 8, 0, 0).unwrap();
    DesktopService::new_for_tests(
        DesktopServiceConfig {
            service_name: "SmsPusher Test".into(),
            preferred_port: 55515,
            history_limit: 1000,
        },
        InMemoryDeviceStore::default(),
        SqliteMessageStore::open_in_memory(1000).unwrap(),
        TokenGenerator::seeded(51),
        VecEventSink::default(),
        Box::new(move || now),
    )
}

#[test]
fn bonjour_txt_record_matches_secure_metadata() {
    let txt = BonjourTxtRecord::new("Test Desktop", true);

    assert_eq!(txt.service_type, "_smspusher._tcp.local.");
    assert_eq!(txt.properties.get("version").map(String::as_str), Some("2"));
    assert_eq!(txt.properties.get("secure").map(String::as_str), Some("v2"));
    assert_eq!(
        txt.properties.get("pake").map(String::as_str),
        Some(smspusher_crypto::PAKE_NAME)
    );
    assert_eq!(
        txt.properties.get("deviceName").map(String::as_str),
        Some("Test Desktop")
    );
    assert_eq!(
        txt.properties.get("pairing").map(String::as_str),
        Some("true")
    );
    assert!(txt.properties.contains_key("pairingSessionId"));
    assert!(txt.properties.contains_key("pairingExpiresAt"));
}

#[test]
fn advertised_mdns_address_prefers_routable_ipv4() {
    let address = preferred_advertised_ipv4([
        Ipv4Addr::LOCALHOST,
        Ipv4Addr::new(169, 254, 1, 20),
        Ipv4Addr::new(192, 0, 2, 10),
    ]);

    assert_eq!(address, Some(Ipv4Addr::new(192, 0, 2, 10)));
}

#[test]
fn advertised_ipv4_auto_uses_first_non_loopback_interface() {
    let address = advertised_ipv4_for_interface(
        [
            LanNetworkInterface::new("lo0", Ipv4Addr::LOCALHOST),
            LanNetworkInterface::new("en0", Ipv4Addr::new(192, 166, 11, 174)),
        ],
        None,
    );

    assert_eq!(address, Some(Ipv4Addr::new(192, 166, 11, 174)));
}

#[test]
fn advertised_ipv4_legacy_selected_id_follows_current_interface_ip() {
    let address = advertised_ipv4_for_interface(
        [
            LanNetworkInterface::new("en0", Ipv4Addr::new(192, 166, 11, 174)),
            LanNetworkInterface::new("en7", Ipv4Addr::new(192, 166, 11, 200)),
        ],
        Some("en7@172.23.191.150"),
    );

    assert_eq!(address, Some(Ipv4Addr::new(192, 166, 11, 200)));
}

#[test]
fn advertised_ipv4_returns_none_when_only_loopback_exists() {
    let address = advertised_ipv4_for_interface(
        [LanNetworkInterface::new("lo0", Ipv4Addr::LOCALHOST)],
        None,
    );

    assert_eq!(address, None);
}

#[test]
fn selected_network_interface_controls_advertised_ipv4() {
    let address = advertised_ipv4_for_interface(
        [
            LanNetworkInterface::new("en0", Ipv4Addr::new(192, 0, 2, 10)),
            LanNetworkInterface::new("en7", Ipv4Addr::new(192, 0, 2, 4)),
        ],
        Some("en7@192.0.2.4"),
    );

    assert_eq!(address, Some(Ipv4Addr::new(192, 0, 2, 4)));
}

#[test]
fn bonjour_txt_record_advertises_selected_ipv4_for_android_discovery() {
    let txt = BonjourTxtRecord::new("Test Desktop", true);
    let properties = txt.properties_for_mdns(Ipv4Addr::new(192, 0, 2, 10));

    assert_eq!(
        properties.get("ipv4").map(String::as_str),
        Some("192.0.2.10")
    );
}

#[test]
fn bonjour_publisher_seeds_auto_publish_with_routable_ipv4() {
    let source = include_str!("../src/mdns.rs");

    assert!(source.contains("default_advertised_ipv4"));
    assert!(source.contains("enable_addr_auto"));
    assert!(source.contains("\"ipv4\""));
    assert!(!source.contains("IpAddr::V4(Ipv4Addr::LOCALHOST)"));
}

#[test]
fn bonjour_publisher_skips_publish_when_no_non_loopback_ipv4_exists() {
    let source = include_str!("../src/mdns.rs");

    assert!(source.contains("no non-loopback IPv4 address available for Bonjour publish; skipping"));
    assert!(!source.contains(".context(\"no non-loopback IPv4 address available for Bonjour publish\")"));
}

#[tokio::test]
async fn server_start_on_ephemeral_port_returns_reachable_health_route() {
    let running = LanServer::start(
        LanServerConfig {
            host: "127.0.0.1".into(),
            preferred_port: 0,
            service_name: "SmsPusher Test".into(),
            pairing_enabled: true,
            publish_bonjour: false,
            advertised_ipv4: None,
        },
        service(),
    )
    .await
    .unwrap();

    assert_ne!(running.port(), 0);

    let response = reqwest::get(format!("http://127.0.0.1:{}/health", running.port()))
        .await
        .unwrap();

    assert_eq!(response.status(), reqwest::StatusCode::OK);
    assert_eq!(response.text().await.unwrap(), r#"{"status":"ok"}"#);
    running.shutdown().await;
}

#[tokio::test]
async fn server_start_shared_updates_transport_status_visible_to_owner() {
    let shared = transport_lan::shared_lan_service(service());
    let running = LanServer::start_shared(
        LanServerConfig {
            host: "127.0.0.1".into(),
            preferred_port: 0,
            service_name: "SmsPusher Test".into(),
            pairing_enabled: true,
            publish_bonjour: false,
            advertised_ipv4: None,
        },
        shared.clone(),
    )
    .await
    .unwrap();

    let status = shared
        .lock()
        .expect("shared service lock")
        .status_snapshot();
    assert_eq!(status.transport.status, "running");
    assert_eq!(status.transport.lan_port, Some(running.port()));

    running.shutdown().await;
}

#[test]
fn lan_transport_emits_tracing_diagnostics() {
    let cargo = include_str!("../Cargo.toml");
    let server = include_str!("../src/server.rs");
    let http = include_str!("../src/http.rs");
    let mdns = include_str!("../src/mdns.rs");

    assert!(cargo.contains("tracing.workspace = true"));
    assert!(server.contains("tracing::info!"));
    assert!(server.contains("tracing::error!"));
    assert!(http.contains("tracing::warn!"));
    assert!(http.contains("secure_messages"));
    assert!(mdns.contains("tracing::info!"));
    assert!(mdns.contains("tracing::warn!"));
}
