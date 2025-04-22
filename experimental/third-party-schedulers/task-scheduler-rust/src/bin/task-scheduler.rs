use anyhow::{Context, Result};
use clap::{self, CommandFactory, Parser};
use rustyline::DefaultEditor;
use std::path::PathBuf;
use task_scheduler::logger;
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
        warn_log!("Starting server without TLS");
    }

    info_log!(
        "Task scheduler server listening on {}{}",
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
