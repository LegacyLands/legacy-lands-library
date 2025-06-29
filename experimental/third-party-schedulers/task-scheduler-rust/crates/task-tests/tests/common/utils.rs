use prost::Message;
use prost_types::Any;
use std::collections::HashMap;
use task_common::proto::taskscheduler::{ListValue, MapValue};
use task_common::proto::TaskRequest;
pub use task_common::proto::taskscheduler::task_scheduler_client::TaskSchedulerClient;
use tonic::transport::Channel;
use base64::Engine;

/// Connect to the server
#[allow(dead_code)]
pub async fn connect_to_server(server_address: &str) -> TaskSchedulerClient<Channel> {
    let channel = tonic::transport::Channel::from_shared(server_address.to_string())
        .expect("Failed to create shared endpoint")
        .connect()
        .await
        .expect("Failed to create channel");

    TaskSchedulerClient::new(channel)
}

/// Convert i32 to Any message
#[allow(dead_code)]
pub fn any_i32(val: i32) -> Any {
    // Encode as JSON for simplicity
    Any {
        type_url: "type.googleapis.com/google.protobuf.Int32Value".to_string(),
        value: serde_json::to_vec(&val).unwrap(),
    }
}

/// Convert bool to Any message
#[allow(dead_code)]
pub fn any_bool(val: bool) -> Any {
    // Encode as JSON for simplicity
    Any {
        type_url: "type.googleapis.com/google.protobuf.BoolValue".to_string(),
        value: serde_json::to_vec(&val).unwrap(),
    }
}

/// Convert String to Any message
#[allow(dead_code)]
pub fn any_string(val: &str) -> Any {
    // Encode as JSON for simplicity
    Any {
        type_url: "type.googleapis.com/google.protobuf.StringValue".to_string(),
        value: serde_json::to_vec(&val).unwrap(),
    }
}

/// Convert byte slice to Any message
#[allow(dead_code)]
pub fn any_bytes(val: &[u8]) -> Any {
    // Encode as base64 string
    Any {
        type_url: "type.googleapis.com/google.protobuf.BytesValue".to_string(),
        value: serde_json::to_vec(&base64::engine::general_purpose::STANDARD.encode(val)).unwrap(),
    }
}

/// Convert Vec<Any> to Any message
#[allow(dead_code)]
pub fn any_list(items: Vec<Any>) -> Any {
    let list_val = ListValue { values: items };
    let mut buf = Vec::new();
    list_val.encode(&mut buf).unwrap();
    Any {
        type_url: "type.googleapis.com/taskscheduler.ListValue".to_string(),
        value: buf,
    }
}

/// Convert HashMap<String, Any> to Any message
#[allow(dead_code)]
pub fn any_map(map: HashMap<String, Any>) -> Any {
    let map_val = MapValue { fields: map };
    let mut buf = Vec::new();
    map_val.encode(&mut buf).unwrap();
    Any {
        type_url: "type.googleapis.com/taskscheduler.MapValue".to_string(),
        value: buf,
    }
}

/// Create task request
#[allow(dead_code)]
pub fn create_task_request(
    task_id: &str,
    method: &str,
    args: Vec<Any>,
    deps: Vec<String>,
    is_async: bool,
) -> TaskRequest {
    TaskRequest {
        task_id: task_id.to_string(),
        method: method.to_string(),
        args,
        deps,
        is_async,
    }
}
