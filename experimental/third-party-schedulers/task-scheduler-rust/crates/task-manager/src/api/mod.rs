pub mod auth_grpc;
pub mod grpc;

pub use auth_grpc::AuthServiceImpl;
pub use grpc::TaskSchedulerService;

// Generated protobuf code
pub mod proto {
    tonic::include_proto!("taskscheduler");
    
    pub const FILE_DESCRIPTOR_SET: &[u8] = tonic::include_file_descriptor_set!("descriptor");
}
