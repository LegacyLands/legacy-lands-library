use clap::Parser;
use task_scheduler::logger;
use task_scheduler::server::service::TaskSchedulerService;
use task_scheduler::tasks::taskscheduler::task_scheduler_server::TaskSchedulerServer;
use task_scheduler::tasks::log_pending_registrations;
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
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let (_guard_file, _guard_stdout) = logger::init_logger();

    log_pending_registrations();

    let args = Args::parse();
    let addr = args.addr.parse()?;
    let service = TaskSchedulerService::default();

    info_log!("Task scheduler server listening on {}.", addr);

    Server::builder()
        .add_service(TaskSchedulerServer::new(service))
        .serve(addr)
        .await?;

    Ok(())
}
