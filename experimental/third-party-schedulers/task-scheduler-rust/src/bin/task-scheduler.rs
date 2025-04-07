use clap::Parser;
use task_scheduler::logger;
use task_scheduler::server::service::TaskSchedulerService;
use task_scheduler::tasks::log_pending_registrations;
use task_scheduler::tasks::taskscheduler::task_scheduler_server::TaskSchedulerServer;
use tonic::transport::Server;

#[macro_use]
extern crate task_scheduler;

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
    let (_guard_file, _guard_stdout) = logger::init_logger();

    log_pending_registrations();

    let args = Args::parse();
    let addr = args.addr.parse()?;
    let service = TaskSchedulerService::default();
    
    info_log!("Starting server with auto-registered tasks");

    let mut server_builder = Server::builder();

    if args.tls {
        info_log!("TLS is enabled (logic temporarily commented out).");
    } else {
        info_log!("TLS is disabled. Running in insecure mode.");
    }

    info_log!("Task scheduler server listening on {}.", addr);
    server_builder
        .add_service(TaskSchedulerServer::new(service))
        .serve(addr)
        .await?;

    Ok(())
} 