pub mod http;
pub mod mdns;
pub mod server;

pub use http::{
    lan_router, lan_router_allowing_v1_for_tests, lan_router_with_shared_service,
    lan_router_with_shared_service_allowing_v1_for_tests, shared_lan_service, SharedLanService,
};
pub use mdns::{
    advertised_ipv4_for_interface, network_interface_candidates, preferred_advertised_ipv4,
    BonjourPublisher, BonjourTxtRecord, LanNetworkInterface, MdnsBonjourPublisher,
};
pub use server::{LanServer, LanServerConfig, RunningLanServer};
