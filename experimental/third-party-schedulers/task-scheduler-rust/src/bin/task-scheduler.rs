use anyhow::{Context, Result};
use clap::{self, CommandFactory, Parser};
use rustyline::DefaultEditor;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;
use task_scheduler::cluster::{Cluster, ClusterConfig};
use task_scheduler::logger;
use task_scheduler::scheduler::{Scheduler, SchedulerConfig};
use task_scheduler::server::service::TaskSchedulerService;
use task_scheduler::tasks::dynamic::init_dynamic_loader;
use task_scheduler::tasks::taskscheduler::task_scheduler_server::TaskSchedulerServer;
use task_scheduler::tasks::{
    list_all_tasks, list_loaded_plugins, load_plugin, log_pending_registrations,
    reload_all_plugins, unload_plugin, DYNAMIC_LOADER,
};
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

    /// Dynamic library directory path
    #[arg(short, long, default_value = "./libraries")]
    library_dir: PathBuf,

    /// Start CLI mode for interactive commands
    #[arg(short, long)]
    cli: bool,

    /// Unique id of this node in the cluster, defaults to the listen address (hostname:port for 0.0.0.0 / ::)
    #[arg(long)]
    node_id: Option<String>,

    /// Comma separated peer URIs, e.g. http://10.0.0.2:50051,https://10.0.0.3:50051
    #[arg(long, value_delimiter = ',')]
    peers: Vec<String>,

    /// CA certificate (PEM) used to verify https peers
    #[arg(long)]
    peer_ca_cert: Option<PathBuf>,

    /// Client certificate (PEM) presented to peers requiring mTLS
    #[arg(long, requires = "peer_tls_key")]
    peer_tls_cert: Option<PathBuf>,

    /// Client private key (PEM) presented to peers requiring mTLS
    #[arg(long, requires = "peer_tls_cert")]
    peer_tls_key: Option<PathBuf>,

    /// Interval between peer heartbeats in milliseconds
    #[arg(long, default_value_t = 3000)]
    heartbeat_interval_ms: u64,

    /// Timeout of short peer requests in milliseconds
    #[arg(long, default_value_t = 5000)]
    peer_timeout_ms: u64,

    /// Default time to wait for task dependencies in milliseconds
    #[arg(long, default_value_t = 30000)]
    dependency_timeout_ms: u64,

    /// Maximum number of concurrently executing tasks, 0 means unlimited
    #[arg(long, default_value_t = 0)]
    max_concurrent_tasks: usize,

    /// How long finished task results are kept, in seconds
    #[arg(long, default_value_t = 600)]
    result_ttl_secs: u64,
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

/// Derives a node id from the listen address. Unspecified addresses (`0.0.0.0`, `::`) are the same
/// on every machine, so the host name is used instead to keep ids unique across the cluster.
fn default_node_id(addr: &std::net::SocketAddr) -> String {
    if !addr.ip().is_unspecified() {
        return addr.to_string();
    }

    let host_name = std::fs::read_to_string("/etc/hostname")
        .ok()
        .map(|name| name.trim().to_string())
        .filter(|name| !name.is_empty())
        .or_else(|| std::env::var("HOSTNAME").ok())
        .or_else(|| std::env::var("COMPUTERNAME").ok());

    match host_name {
        Some(host_name) => format!("{}:{}", host_name, addr.port()),
        None => {
            warn_log!("Could not determine the host name, set --node-id explicitly to get a stable node id");
            format!("node-{}:{}", std::process::id(), addr.port())
        }
    }
}

fn run_cli_mode() -> Result<()> {
    let rt = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap();

    #[derive(clap::Parser)]
    #[command(name = "task")]
    enum Command {
        Help,

        List,

        Plugins,

        Reload,

        Load { name: String },

        Unload { name: String },

        Exit,
    }

    fn handle_help() {
        Command::command().print_help().unwrap();
        println!();
    }

    fn handle_list() {
        let tasks = list_all_tasks();
        info_log!("Registered tasks ({}):", tasks.len());
        for (name, task_type, is_dynamic, timestamp) in tasks {
            info_log!(
                "  {} - Type: {}, Dynamic: {}, Registered: {}",
                name,
                task_type,
                is_dynamic,
                timestamp
            );
        }
    }

    fn handle_plugins() {
        let plugins = list_loaded_plugins();
        info_log!("Loaded plugins ({}):", plugins.len());
        for (name, tasks) in plugins {
            info_log!("  {} - Tasks: {}", name, tasks.join(", "));
        }
    }

    fn handle_reload(rt: &tokio::runtime::Runtime) {
        match rt.block_on(async { reload_all_plugins() }) {
            Ok(plugins) => {
                let total_tasks: usize = plugins.values().map(|v| v.len()).sum();
                info_log!(
                    "Reloaded {} plugins with {} tasks",
                    plugins.len(),
                    total_tasks
                );
                for (name, tasks) in plugins {
                    info_log!("  {} - Tasks: {}", name, tasks.join(", "));
                }
            }
            Err(e) => {
                error_log!("Error reloading plugins: {}", e);
            }
        }
    }

    fn handle_load(rt: &tokio::runtime::Runtime, name: String) {
        match rt.block_on(async { load_plugin(&name) }) {
            Ok(tasks) => {
                info_log!("Loaded plugin '{}' with {} tasks:", name, tasks.len());
                for task in tasks {
                    info_log!("  {}", task);
                }
            }
            Err(e) => {
                error_log!("Error loading plugin '{}': {}", name, e);
            }
        }
    }

    fn handle_unload(rt: &tokio::runtime::Runtime, name: String) {
        match rt.block_on(async { unload_plugin(&name) }) {
            Ok(tasks) => {
                info_log!("Unloaded plugin '{}' with {} tasks:", name, tasks.len());
                for task in tasks {
                    info_log!("  {}", task);
                }
            }
            Err(e) => {
                error_log!("Error unloading plugin '{}': {}", name, e);
            }
        }
    }

    fn handle_exit() -> bool {
        info_log!("Exiting CLI mode. Server will continue running");
        true
    }

    std::thread::spawn(move || {
        let mut rl = DefaultEditor::new().unwrap();
        info_log!("Task Scheduler CLI Mode");
        info_log!("Type 'help' for available commands");

        loop {
            let readline = rl.readline("task> ");
            match readline {
                Ok(line) => {
                    let line = line.trim();
                    if line.is_empty() {
                        continue;
                    }

                    let parts: Vec<&str> = line.split_whitespace().collect();
                    if parts.is_empty() {
                        continue;
                    }

                    let mut args = vec!["task"];
                    args.extend(parts);

                    let result = Command::try_parse_from(args);

                    match result {
                        Ok(command) => {
                            let should_exit = match command {
                                Command::Help => {
                                    handle_help();
                                    false
                                }
                                Command::List => {
                                    handle_list();
                                    false
                                }
                                Command::Plugins => {
                                    handle_plugins();
                                    false
                                }
                                Command::Reload => {
                                    handle_reload(&rt);
                                    false
                                }
                                Command::Load { name } => {
                                    handle_load(&rt, name);
                                    false
                                }
                                Command::Unload { name } => {
                                    handle_unload(&rt, name);
                                    false
                                }
                                Command::Exit => handle_exit(),
                            };

                            if should_exit {
                                break;
                            }
                        }
                        Err(e) => {
                            if !e.to_string().contains("help") {
                                warn_log!("Unknown command: {}", line);
                                info_log!("Type 'help' for available commands");
                            }
                        }
                    }
                }
                Err(_) => {
                    error_log!("Error reading input");
                    break;
                }
            }
        }
    });

    Ok(())
}

#[tokio::main]
async fn main() -> Result<()> {
    let (_guard_file, _guard_stdout) = logger::init_logger();

    log_pending_registrations();

    let args = Args::parse();

    init_dynamic_loader(args.library_dir.clone());

    {
        let loader = DYNAMIC_LOADER.lock();
        if let Some(loader) = loader.as_ref() {
            match loader.scan_and_load_all() {
                Ok(plugins) => {
                    let total_tasks: usize = plugins.values().map(|v| v.len()).sum();
                    if !plugins.is_empty() {
                        info_log!(
                            "Loaded {} dynamic library plugins with {} tasks",
                            plugins.len(),
                            total_tasks
                        );
                    }
                }
                Err(e) => {
                    error_log!("Error loading dynamic libraries: {}", e);
                }
            }
        }
    }

    if args.cli {
        run_cli_mode()?;
    }

    let addr = args
        .addr
        .parse()
        .with_context(|| format!("Failed to parse address: {}", args.addr))?;

    let peer_ca_cert = match &args.peer_ca_cert {
        Some(path) => Some(load_ca_cert(path).await?),
        None => None,
    };
    let peer_identity = match (&args.peer_tls_cert, &args.peer_tls_key) {
        (Some(cert_path), Some(key_path)) => Some(load_identity(cert_path, key_path).await?),
        _ => None,
    };
    let cluster = Cluster::new(ClusterConfig {
        peers: args.peers.clone(),
        peer_ca_cert,
        peer_identity,
        heartbeat_interval: Duration::from_millis(args.heartbeat_interval_ms),
        request_timeout: Duration::from_millis(args.peer_timeout_ms),
    })
    .map_err(|e| anyhow::anyhow!("Failed to configure cluster: {}", e))?;

    let node_id = args
        .node_id
        .clone()
        .unwrap_or_else(|| default_node_id(&addr));
    let scheduler = Arc::new(Scheduler::new(
        SchedulerConfig {
            node_id: node_id.clone(),
            default_dependency_timeout: Duration::from_millis(args.dependency_timeout_ms),
            max_concurrent_tasks: args.max_concurrent_tasks,
            result_ttl: Duration::from_secs(args.result_ttl_secs),
            ..SchedulerConfig::default()
        },
        Arc::new(cluster),
    ));
    scheduler.start_background_tasks();

    if args.peers.is_empty() {
        info_log!(
            "Node '{}' running standalone (no peers configured)",
            node_id
        );
    } else {
        info_log!(
            "Node '{}' joining cluster with peers: {}",
            node_id,
            args.peers.join(", ")
        );
    }

    let service = TaskSchedulerService::new(scheduler);

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
        warn_log!("Starting server without TLS");
    }

    info_log!(
        "Task scheduler server listening on {}{}",
        addr,
        if tls_enabled { " (TLS)" } else { "" }
    );

    server_builder
        .add_service(TaskSchedulerServer::new(service))
        .serve_with_shutdown(addr, async {
            let _ = tokio::signal::ctrl_c().await;
            info_log!("Shutdown signal received, stopping server");
        })
        .await
        .context("Failed to start Tonic server")?;

    Ok(())
}
