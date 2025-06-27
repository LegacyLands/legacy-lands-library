/// Generated protobuf types
pub mod taskscheduler {
    tonic::include_proto!("taskscheduler");
}

pub mod crd {
    tonic::include_proto!("taskscheduler.crd");
}

// Re-export commonly used types
pub use taskscheduler::{
    task_response::Status as TaskResponseStatus, ResultRequest, ResultResponse, TaskRequest,
    TaskResponse,
};

// CRD types are defined in crd.rs, not generated from proto
