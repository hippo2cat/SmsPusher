use crate::{
    http::{lan_router_with_shared_service, shared_lan_service, SharedLanService},
    mdns::{BonjourPublisher, BonjourTxtRecord, MdnsBonjourPublisher},
};
use anyhow::{Context, Result};
use smspusher_core::{DeviceStore, MessageStore, SecretStore};
use smspusher_service::{DesktopService, ServiceEventSink};
use std::net::{Ipv4Addr, SocketAddr};
use tokio::{net::TcpListener, sync::oneshot, task::JoinHandle};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LanServerConfig {
    pub host: String,
    pub preferred_port: u16,
    pub service_name: String,
    pub pairing_enabled: bool,
    pub publish_bonjour: bool,
    pub advertised_ipv4: Option<Ipv4Addr>,
}

pub struct LanServer;

pub struct RunningLanServer {
    port: u16,
    shutdown: Option<oneshot::Sender<()>>,
    task: JoinHandle<()>,
    bonjour: Option<MdnsBonjourPublisher>,
}

impl LanServer {
    pub async fn start<DS, MS, ES, N, SS>(
        config: LanServerConfig,
        service: DesktopService<DS, MS, ES, N, SS>,
    ) -> Result<RunningLanServer>
    where
        DS: DeviceStore + Send + 'static,
        MS: MessageStore + Send + 'static,
        SS: SecretStore + Send + 'static,
        ES: ServiceEventSink,
        N: Fn() -> chrono::DateTime<chrono::Utc> + Send + Sync + 'static,
    {
        Self::start_shared(config, shared_lan_service(service)).await
    }

    pub async fn start_shared<DS, MS, ES, N, SS>(
        config: LanServerConfig,
        service: SharedLanService<DS, MS, ES, N, SS>,
    ) -> Result<RunningLanServer>
    where
        DS: DeviceStore + Send + 'static,
        MS: MessageStore + Send + 'static,
        SS: SecretStore + Send + 'static,
        ES: ServiceEventSink,
        N: Fn() -> chrono::DateTime<chrono::Utc> + Send + Sync + 'static,
    {
        tracing::info!(
            host = %config.host,
            preferred_port = config.preferred_port,
            service_name = %config.service_name,
            publish_bonjour = config.publish_bonjour,
            advertised_ipv4 = ?config.advertised_ipv4,
            "starting LAN transport"
        );
        let bind_addr: SocketAddr = format!("{}:{}", config.host, config.preferred_port)
            .parse()
            .context("invalid LAN server bind address")?;
        let listener = TcpListener::bind(bind_addr)
            .await
            .map_err(|error| {
                tracing::error!(bind_addr = %bind_addr, error = %error, "failed to bind LAN server");
                error
            })
            .context("failed to bind LAN server")?;
        let port = listener
            .local_addr()
            .context("failed to inspect LAN server port")?
            .port();
        tracing::info!(port, "LAN transport bound");

        let status = {
            let mut service = service.lock().expect("desktop service lock");
            service.set_lan_port(Some(port));
            service.status_snapshot()
        };
        let app = lan_router_with_shared_service(service);
        let (shutdown_tx, shutdown_rx) = oneshot::channel();
        let task = tokio::spawn(async move {
            let result = axum::serve(listener, app)
                .with_graceful_shutdown(async {
                    let _ = shutdown_rx.await;
                })
                .await;
            if let Err(error) = result {
                tracing::error!(error = %error, "LAN server stopped with error");
            }
        });

        let mut bonjour = None;
        if config.publish_bonjour {
            tracing::info!(
                port,
                service_name = %status.service_name,
                advertised_ipv4 = ?config.advertised_ipv4,
                "publishing LAN Bonjour service"
            );
            let mut publisher = MdnsBonjourPublisher::default();
            publisher.publish(
                BonjourTxtRecord::secure(
                    status.service_name,
                    config.pairing_enabled,
                    status.pairing_code.session_id,
                    status.pairing_code.expires_at,
                ),
                port,
                config.advertised_ipv4,
            )?;
            bonjour = Some(publisher);
        }

        Ok(RunningLanServer {
            port,
            shutdown: Some(shutdown_tx),
            task,
            bonjour,
        })
    }
}

impl RunningLanServer {
    pub fn port(&self) -> u16 {
        self.port
    }

    pub async fn shutdown(mut self) {
        tracing::info!(port = self.port, "LAN transport shutdown requested");
        if let Some(sender) = self.shutdown.take() {
            let _ = sender.send(());
        }
        let _ = self.task.await;
        if let Some(mut bonjour) = self.bonjour.take() {
            bonjour.stop();
        }
        tracing::info!(port = self.port, "LAN transport shutdown completed");
    }
}
