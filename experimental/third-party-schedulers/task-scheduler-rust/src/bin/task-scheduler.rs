use anyhow::{Context, Result};
use clap::Parser;
use std::path::PathBuf;
use task_scheduler::logger;
use task_scheduler::server::service::TaskSchedulerService;
use task_scheduler::tasks::log_pending_registrations;
use task_scheduler::tasks::taskscheduler::task_scheduler_server::TaskSchedulerServer;
use tokio::fs;
use tonic::transport::{Certificate, Identity, Server, ServerTlsConfig};

#[macro_use]
extern crate task_scheduler;

#[allow(unused_imports)]
use task_scheduler::tasks::builtin;

#[derive(Parser)]
#[command(author, version, about, long_about = None)]
struct Args {
    #[arg(short, long, default_value = "127.0.0.1:50051")]
    addr: String,

    /// Path to the TLS server certificate file (PEM format). Required for TLS.
    #[arg(long, requires = "tls_key")]
    tls_cert: Option<PathBuf>,

    /// Path to the TLS server private key file (PEM format). Required for TLS.
    #[arg(long, requires = "tls_cert")]
    tls_key: Option<PathBuf>,

    /// Path to the optional client CA certificate file (PEM format) for mTLS.
    #[arg(long)]
    tls_ca_cert: Option<PathBuf>,
}

async fn load_identity(cert_path: &PathBuf, key_path: &PathBuf) -> Result<Identity> {
    let cert_pem = fs::read(cert_path)
        .await
        .with_context(|| format!("Failed to read certificate file: {}", cert_path.display()))?;
    let key_pem = fs::read(key_path)
        .await
        .with_context(|| format!("Failed to read key file: {}", key_path.display()))?;
    Ok(Identity::from_pem(cert_pem, key_pem))
}

async fn load_ca_cert(ca_path: &PathBuf) -> Result<Certificate> {
    let ca_pem = fs::read(ca_path)
        .await
        .with_context(|| format!("Failed to read CA certificate file: {}", ca_path.display()))?;
    Ok(Certificate::from_pem(ca_pem))
}

#[tokio::main]
async fn main() -> Result<()> {
    let (_guard_file, _guard_stdout) = logger::init_logger();

    log_pending_registrations();

    let args = Args::parse();
    let addr = args
        .addr
        .parse()
        .with_context(|| format!("Failed to parse address: {}", args.addr))?;
    let service = TaskSchedulerService::default();

    let mut server_builder = Server::builder();
    let mut tls_enabled = false;

    if let (Some(cert_path), Some(key_path)) = (&args.tls_cert, &args.tls_key) {
        let server_identity = load_identity(cert_path, key_path).await?;
        let mut tls_config = ServerTlsConfig::new().identity(server_identity);

        if let Some(ca_path) = &args.tls_ca_cert {
            let client_ca_cert = load_ca_cert(ca_path).await?;
            tls_config = tls_config.client_ca_root(client_ca_cert);
            info_log!(
                "TLS enabled with mTLS (client certificate required). CA: {}",
                ca_path.display()
            );
        } else {
            info_log!(
                "TLS enabled (no client certificate required). Cert: {}, Key: {}",
                cert_path.display(),
                key_path.display()
            );
        }

        server_builder = server_builder
            .tls_config(tls_config)
            .context("Failed to apply TLS configuration")?;
        tls_enabled = true;
    } else {
        warn_log!("Starting server without TLS.");
    }

    info_log!(
        "Task scheduler server listening on {}{}.",
        addr,
        if tls_enabled { " (TLS)" } else { "" }
    );

    server_builder
        .add_service(TaskSchedulerServer::new(service))
        .serve(addr)
        .await
        .context("Failed to start Tonic server")?;

    Ok(())
}
