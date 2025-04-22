use prost::Message;
use prost_types::Any;
use std::collections::HashMap;
use task_scheduler::models::wrappers::{BoolValue, BytesValue, Int32Value, StringValue};
use task_scheduler::tasks::taskscheduler::{
    task_scheduler_client::TaskSchedulerClient, ListValue, MapValue, TaskRequest,
};
use tonic::transport::Channel;

/// Connect to the server
pub async fn connect_to_server(server_address: &str) -> TaskSchedulerClient<Channel> {
    let channel = tonic::transport::Channel::from_shared(server_address.to_string())
        .expect("Failed to create shared endpoint")
        .connect()
        .await
        .expect("Failed to create channel");

    TaskSchedulerClient::new(channel)
}

/// Convert i32 to Any message
pub fn any_i32(val: i32) -> Any {
    Any {
        type_url: "type.googleapis.com/google.protobuf.Int32Value".to_string(),
        value: Int32Value { value: val }.encode_to_vec(),
    }
}

/// Convert bool to Any message
#[allow(dead_code)]
pub fn any_bool(val: bool) -> Any {
    Any {
        type_url: "type.googleapis.com/google.protobuf.BoolValue".to_string(),
        value: BoolValue { value: val }.encode_to_vec(),
    }
}

/// Convert String to Any message
#[allow(dead_code)]
pub fn any_string(val: &str) -> Any {
    Any {
        type_url: "type.googleapis.com/google.protobuf.StringValue".to_string(),
        value: StringValue {
            value: val.to_string(),
        }
        .encode_to_vec(),
    }
}

/// Convert byte slice to Any message
#[allow(dead_code)]
pub fn any_bytes(val: &[u8]) -> Any {
    Any {
        type_url: "type.googleapis.com/google.protobuf.BytesValue".to_string(),
        value: BytesValue {
            value: val.to_vec(),
        }
        .encode_to_vec(),
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
