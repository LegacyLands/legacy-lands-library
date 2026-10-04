use std::future::Future;
use std::pin::Pin;
use std::sync::OnceLock;
use task_scheduler::error::{Result, TaskError};
use task_scheduler::models::ArgValue;

/// Example synchronous task: Calculate the product of multiple integers
#[no_mangle]
pub unsafe fn multiply(args: Vec<ArgValue>) -> Result<String> {
    let product: Result<i32> = args.into_iter().try_fold(1, |acc, v| match v {
        ArgValue::Int32(n) => Ok(acc * n),
        _ => Err(TaskError::InvalidArguments(
            "Expected Int32 for multiply".to_string(),
        )),
    });

    Ok(product?.to_string())
}

/// Runtime owned by this plugin.
///
/// A plugin links its own copy of tokio, which cannot see the host runtime, so timers and I/O
/// must run on a plugin-owned runtime. The host only awaits the returned join handle.
fn plugin_runtime() -> &'static tokio::runtime::Runtime {
    static RUNTIME: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(1)
            .enable_time()
            .build()
            .expect("Failed to build plugin runtime")
    })
}

/// Example asynchronous task: Return result with delay
#[no_mangle]
pub unsafe fn delayed_echo(args: Vec<ArgValue>) -> Pin<Box<dyn Future<Output = String> + Send>> {
    let handle = plugin_runtime().spawn(async move {
        // Convert parameters
        let msg = args
            .into_iter()
            .filter_map(|arg| match arg {
                ArgValue::String(s) => Some(s),
                _ => None,
            })
            .collect::<Vec<_>>()
            .join(" ");

        tokio::time::sleep(tokio::time::Duration::from_millis(500)).await;

        // Return result directly
        format!(
            "Echo after delay: {}",
            if msg.is_empty() { "no message" } else { &msg }
        )
    });

    Box::pin(async move {
        handle
            .await
            .unwrap_or_else(|e| format!("Error: delayed_echo failed: {}", e))
    })
}

// Get multiply function pointer
#[no_mangle]
pub unsafe fn get_multiply_ptr() -> usize {
    multiply as usize
}

// Get delayed_echo function pointer
#[no_mangle]
pub unsafe fn get_delayed_echo_ptr() -> usize {
    delayed_echo as usize
}

// Task entry point information
type TaskEntryInfo = (&'static str, bool, usize);

/// Initialize plugin, return task list
#[no_mangle]
pub unsafe fn init_plugin() -> &'static [TaskEntryInfo] {
    static mut TASKS: [TaskEntryInfo; 2] = [("multiply", false, 0), ("delayed_echo", true, 0)];

    TASKS[0].2 = get_multiply_ptr();
    TASKS[1].2 = get_delayed_echo_ptr();

    &TASKS
}
