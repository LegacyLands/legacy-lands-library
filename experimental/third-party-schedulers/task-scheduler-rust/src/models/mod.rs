pub mod wrappers;

#[derive(Debug, Clone)]
pub struct TaskInfo {
    pub status: i32, // 0: PENDING, 1: SUCCESS, 2: FAILED
    pub result: Option<String>,
}

#[derive(Debug, Clone)]
pub struct TaskResult {
    pub status: i32,
    pub value: String,
}

#[derive(Clone)]
pub struct TaskRequest {
    pub task_id: String,
    pub method: String,
    pub args: Vec<prost_types::Any>,
    pub is_async: bool,
}

#[derive(Clone)]
pub struct TaskResponse {
    pub task_id: String,
    pub status: i32,
    pub result: String,
}

#[derive(Clone)]
pub struct ResultRequest {
    pub task_id: String,
}

#[derive(Clone)]
pub struct ResultResponse {
    pub result: String,
    pub is_ready: bool,
}

#[derive(Debug, Clone)]
pub enum ArgValue {
    Int32(i32),
    Int64(i64),
    UInt32(u32),
    UInt64(u64),
    Float(f32),
    Double(f64),
    Bool(bool),
    String(String),
    Bytes(Vec<u8>),
    Array(Vec<ArgValue>),
    Map(std::collections::HashMap<String, ArgValue>),
}
