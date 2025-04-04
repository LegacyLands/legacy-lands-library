use clap::Parser;
use task_scheduler::server::service::TaskSchedulerService;
use task_scheduler::tasks::taskscheduler::task_scheduler_server::TaskSchedulerServer;
use tonic::transport::{Identity, Server, ServerTlsConfig};
use std::fs::File;
use std::io::BufReader;
use rustls_pemfile::{certs, pkcs8_private_keys};
use tokio_rustls::rustls::{Certificate, PrivateKey, ServerConfig};
use std::sync::Arc;

#[allow(unused_imports)]
use task_scheduler::tasks::builtin;

#[derive(Parser)]
#[command(author, version, about, long_about = None)]
struct Args {
    #[arg(short, long, default_value = "127.0.0.1:50051")]
    addr: String,

    #[arg(long)]
    tls: bool,

    #[arg(long, requires = "tls")]
    cert_path: Option<String>,

    #[arg(long, requires = "tls")]
    key_path: Option<String>,
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args = Args::parse();
    let addr = args.addr.parse()?;
    let service = TaskSchedulerService::default();
    
    println!("Starting server with auto-registered tasks");

    let mut server_builder = Server::builder();

    if args.tls {
        println!("TLS is enabled.");
        let cert_path = args.cert_path.expect("--cert-path is required when --tls is enabled");
        let key_path = args.key_path.expect("--key-path is required when --tls is enabled");

        println!("Loading server certificate from: {}", cert_path);
        println!("Loading server private key from: {}", key_path);

        let cert_file = File::open(&cert_path)?;
        let mut cert_reader = BufReader::new(cert_file);
        let cert_chain = certs(&mut cert_reader)?
            .into_iter()
            .map(Certificate)
            .collect();

        let key_file = File::open(&key_path)?;
        let mut key_reader = BufReader::new(key_file);
        let mut keys = pkcs8_private_keys(&mut key_reader)?;
        if keys.is_empty() {
            key_reader.seek(std::io::SeekFrom::Start(0))?;
            keys = rustls_pemfile::rsa_private_keys(&mut key_reader)?;
            if keys.is_empty() {
                 return Err("No PKCS8 or RSA private key found in key file".into());
             }
        }
        let private_key = PrivateKey(keys.remove(0));

        let tls_config = Arc::new(ServerConfig::builder()
            .with_safe_defaults()
            .with_no_client_auth()
            .with_single_cert(cert_chain, private_key)?);

        let server_tls_config = ServerTlsConfig::new().rustls_server_config(tls_config);

        server_builder = server_builder.tls_config(server_tls_config)?;
        println!("TLS configuration loaded successfully.");
    } else {
        println!("TLS is disabled. Running in insecure mode.");
    }

    println!("Task scheduler server listening on {}.", addr);
    server_builder
        .add_service(TaskSchedulerServer::new(service))
        .serve(addr)
        .await?;

    Ok(())
} 