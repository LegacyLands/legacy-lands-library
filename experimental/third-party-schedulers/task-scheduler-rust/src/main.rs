use clap::Parser;
use task_scheduler::server::service::TaskSchedulerService;
use task_scheduler::tasks::taskscheduler::task_scheduler_server::TaskSchedulerServer;
use tonic::transport::Server;

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
    let args = Args::parse();
    let addr = args.addr.parse()?;
    let service = TaskSchedulerService::default();

    println!("Task scheduler server listening on {}.", addr);

    Server::builder()
        .add_service(TaskSchedulerServer::new(service))
        .serve(addr)
        .await?;

    Ok(())
}
