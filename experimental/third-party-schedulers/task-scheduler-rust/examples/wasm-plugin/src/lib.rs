use serde::{Deserialize, Serialize};
use std::alloc::{alloc, dealloc, Layout};

// Use wee_alloc as the global allocator for smaller binary size
#[global_allocator]
static ALLOC: wee_alloc::WeeAlloc = wee_alloc::WeeAlloc::INIT;

// Result buffer for returning data
static mut RESULT_BUFFER: Option<Vec<u8>> = None;

/// Input structure for the echo plugin
#[derive(Debug, Deserialize)]
struct EchoInput {
    message: String,
    repeat: Option<u32>,
}

/// Output structure for the echo plugin
#[derive(Debug, Serialize)]
struct EchoOutput {
    echoed: Vec<String>,
    count: u32,
}

/// Initialize the plugin
#[no_mangle]
pub extern "C" fn init() {
    // Perform any initialization here
    unsafe {
        RESULT_BUFFER = Some(Vec::new());
    }
}

/// Shutdown the plugin
#[no_mangle]
pub extern "C" fn shutdown() {
    // Clean up resources
    unsafe {
        RESULT_BUFFER = None;
    }
}

/// Allocate memory for input data
#[no_mangle]
pub extern "C" fn alloc(size: i32) -> *mut u8 {
    if size <= 0 {
        return std::ptr::null_mut();
    }
    
    let layout = Layout::from_size_align(size as usize, 1).unwrap();
    unsafe { alloc(layout) }
}

/// Free allocated memory
#[no_mangle]
pub extern "C" fn free(ptr: *mut u8) {
    if ptr.is_null() {
        return;
    }
    
    // We don't know the original size, so we can't properly deallocate
    // In a real implementation, you'd want to track allocations
}

/// Execute the plugin logic
#[no_mangle]
pub extern "C" fn execute(ptr: *mut u8, len: i32) -> *mut u8 {
    if ptr.is_null() || len <= 0 {
        return std::ptr::null_mut();
    }
    
    // Read input data
    let input_slice = unsafe { std::slice::from_raw_parts(ptr, len as usize) };
    
    // Parse input JSON
    let input: EchoInput = match serde_json::from_slice(input_slice) {
        Ok(input) => input,
        Err(e) => {
            let error_output = serde_json::json!({
                "error": format!("Failed to parse input: {}", e)
            });
            store_result(serde_json::to_vec(&error_output).unwrap());
            return get_result_ptr();
        }
    };
    
    // Execute plugin logic
    let repeat_count = input.repeat.unwrap_or(1);
    let mut echoed = Vec::new();
    
    for _ in 0..repeat_count {
        echoed.push(input.message.clone());
    }
    
    let output = EchoOutput {
        echoed: echoed.clone(),
        count: echoed.len() as u32,
    };
    
    // Serialize output
    let output_json = match serde_json::to_vec(&output) {
        Ok(json) => json,
        Err(e) => {
            let error_output = serde_json::json!({
                "error": format!("Failed to serialize output: {}", e)
            });
            serde_json::to_vec(&error_output).unwrap()
        }
    };
    
    // Store result and return pointer
    store_result(output_json);
    get_result_ptr()
}

/// Get the length of the result
#[no_mangle]
pub extern "C" fn result_len(_ptr: *mut u8) -> i32 {
    unsafe {
        match &RESULT_BUFFER {
            Some(buffer) => buffer.len() as i32,
            None => 0,
        }
    }
}

/// Store result in the global buffer
fn store_result(data: Vec<u8>) {
    unsafe {
        RESULT_BUFFER = Some(data);
    }
}

/// Get pointer to the result buffer
fn get_result_ptr() -> *mut u8 {
    unsafe {
        match &mut RESULT_BUFFER {
            Some(buffer) => buffer.as_mut_ptr(),
            None => std::ptr::null_mut(),
        }
    }
}

/// Host function imports
extern "C" {
    /// Log a message to the host
    fn log(ptr: *const u8, len: i32);
    
    /// Get current timestamp in milliseconds
    fn get_time() -> i64;
}

/// Helper function to log messages
#[allow(dead_code)]
fn host_log(message: &str) {
    unsafe {
        log(message.as_ptr(), message.len() as i32);
    }
}

/// Advanced example: A transform plugin
#[derive(Debug, Deserialize)]
struct TransformInput {
    operation: String,
    data: serde_json::Value,
}

#[derive(Debug, Serialize)]
struct TransformOutput {
    result: serde_json::Value,
    operation: String,
    timestamp: i64,
}

/// Alternative execute function for transform operations
#[allow(dead_code)]
pub extern "C" fn execute_transform(ptr: *mut u8, len: i32) -> *mut u8 {
    if ptr.is_null() || len <= 0 {
        return std::ptr::null_mut();
    }
    
    let input_slice = unsafe { std::slice::from_raw_parts(ptr, len as usize) };
    let input: TransformInput = match serde_json::from_slice(input_slice) {
        Ok(input) => input,
        Err(_) => return std::ptr::null_mut(),
    };
    
    let result = match input.operation.as_str() {
        "uppercase" => {
            if let Some(s) = input.data.as_str() {
                serde_json::Value::String(s.to_uppercase())
            } else {
                input.data
            }
        }
        "lowercase" => {
            if let Some(s) = input.data.as_str() {
                serde_json::Value::String(s.to_lowercase())
            } else {
                input.data
            }
        }
        "reverse" => {
            if let Some(s) = input.data.as_str() {
                serde_json::Value::String(s.chars().rev().collect())
            } else {
                input.data
            }
        }
        "double" => {
            if let Some(n) = input.data.as_f64() {
                serde_json::Value::from(n * 2.0)
            } else {
                input.data
            }
        }
        _ => input.data,
    };
    
    let output = TransformOutput {
        result,
        operation: input.operation,
        timestamp: unsafe { get_time() },
    };
    
    let output_json = serde_json::to_vec(&output).unwrap();
    store_result(output_json);
    get_result_ptr()
}