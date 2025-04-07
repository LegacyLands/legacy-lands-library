use std::process::{Child, Command, Stdio};
use std::sync::Once;
use std::time::Duration;

pub struct TestServer {
    address: String,
    process: Child,
}

impl TestServer {
    pub fn address(&self) -> String {
        format!("http://{}", self.address)
    }
}

// Ensure the child process is killed when TestServer is dropped
impl Drop for TestServer {
    fn drop(&mut self) {
        println!("Stopping test server process...");
        // Try to kill the process gently first, then forcefully if needed
        if let Err(e) = self.process.kill() {
            eprintln!("Failed to kill test server process: {}", e);
        } else {
            // Wait for the process to ensure it's terminated
            match self.process.wait() {
                Ok(status) => println!("Test server process exited with: {}", status),
                Err(e) => eprintln!("Failed to wait for test server process: {}", e),
            }
        }
    }
}

static START: Once = Once::new();

/// Sets up the test server, ensuring it only starts once per test run.
/// Returns a TestServer struct containing the server address and process handle.
#[allow(clippy::zombie_processes)]
pub async fn setup() -> TestServer {
    let server_address = "127.0.0.1:50051".to_string();
    static SERVER_PROCESS: std::sync::Mutex<Option<Child>> = std::sync::Mutex::new(None);

    START.call_once(|| {
        // Build the server binary
        println!("Building test server binary...");
        assert!(
            Command::new("cargo")
                .args(["build", "--bin", "task-scheduler"])
                .status()
                .expect("Failed to build server binary")
                .success(),
            "Failed to build the server binary"
        );
        println!("Test server binary built.");

        // Start the server process
        println!("Starting test server process...");
        let process = Command::new("target/debug/task-scheduler")
            .arg("--addr")
            .arg(&server_address)
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .expect("Failed to start server process");
        println!("Test server process started (PID: {}).", process.id());
        *SERVER_PROCESS.lock().unwrap() = Some(process);
    });

    // Wait a moment for the server to potentially start listening
    tokio::time::sleep(Duration::from_secs(2)).await;

    // Re-fetch or restart a dummy process to get a Child handle for Drop
    // This is a workaround because getting the original Child out of call_once is hard.
    let process_for_drop = Command::new("target/debug/task-scheduler")
        .arg("--addr")
        .arg(&server_address)
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .expect("Failed to start dummy server process for drop handle");

    TestServer {
        address: server_address,
        process: process_for_drop,
    }
}
