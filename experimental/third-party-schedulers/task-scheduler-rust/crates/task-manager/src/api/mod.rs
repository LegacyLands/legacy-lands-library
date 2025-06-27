pub mod grpc;

pub use grpc::TaskSchedulerService;

// Generated protobuf code
pub mod proto {
    tonic::include_proto!("taskscheduler");
}
