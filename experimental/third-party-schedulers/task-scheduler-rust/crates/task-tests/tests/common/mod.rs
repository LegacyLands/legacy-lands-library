use std::process::{Child, Command, Stdio};
use std::sync::{Mutex, Once};
use std::time::Duration;

pub mod utils;
pub mod test_environment;

pub use test_environment::TestEnvironment;

// Define base test port
pub const TEST_PORT: u16 = 50051;
static INIT: Once = Once::new();
static PORT_COUNTER: Mutex<u16> = Mutex::new(0);

/// Get the next available port
fn get_next_port() -> u16 {
    let mut counter = PORT_COUNTER.lock().unwrap();
    *counter += 1;
    TEST_PORT + *counter
}

pub struct TestServer {
    process: Child,
    port: u16,
}

impl TestServer {
    #[allow(dead_code)]
    pub fn address(&self) -> String {
        format!("http://127.0.0.1:{}", self.port)
    }
}

impl Drop for TestServer {
    fn drop(&mut self) {
        println!(
            "Stopping test server process (PID: {}) on port {}...",
            self.process.id(),
            self.port
        );
        if let Err(e) = self.process.kill() {
            eprintln!(
                "Failed to kill test server process (PID: {}, Port: {}): {}",
                self.process.id(),
                self.port,
                e
            );
        } else {
            match self.process.wait() {
                Ok(status) => println!(
                    "Test server process (PID: {}, Port: {}) exited with: {}",
                    self.process.id(),
                    self.port,
                    status
                ),
                Err(e) => eprintln!(
                    "Failed to wait for test server process (PID: {}, Port: {}): {}",
                    self.process.id(),
                    self.port,
                    e
                ),
            }
        }
    }
}

/// Set up test server with automatically assigned port
#[allow(clippy::zombie_processes)]
#[allow(dead_code)]
pub async fn setup() -> TestServer {
    // Ensure binary is built only once
    INIT.call_once(|| {
        println!("Ensuring test server binary is built...");
        assert!(
            Command::new("cargo")
                .args(["build", "--bin", "task-scheduler"])
                .status()
                .expect("Failed to build server binary")
                .success(),
            "Failed to build the server binary"
        );
    });

    let port = get_next_port();
    println!("Starting test server process on port {}...", port);
    let process = Command::new("target/debug/task-scheduler")
        .arg("--addr")
        .arg(format!("127.0.0.1:{}", port))
        .stdout(Stdio::inherit())
        .stderr(Stdio::inherit())
        .spawn()
        .expect("Failed to start server process");
    println!(
        "Test server process started (PID: {}, Port: {}).",
        process.id(),
        port
    );

    // Wait for server to start
    tokio::time::sleep(Duration::from_millis(700)).await;

    TestServer { process, port }
}

/// Set up test server with custom options
#[allow(clippy::zombie_processes)]
#[allow(dead_code)]
pub async fn setup_with_options(port: Option<u16>, library_dir: Option<&str>) -> TestServer {
    // Ensure binary is built only once
    INIT.call_once(|| {
        println!("Ensuring test server binary is built...");
        assert!(
            Command::new("cargo")
                .args(["build", "--bin", "task-scheduler"])
                .status()
                .expect("Failed to build server binary")
                .success(),
            "Failed to build the server binary"
        );
    });

    let port = port.unwrap_or_else(get_next_port);
    println!("Starting test server process on port {}...", port);
    let mut command = Command::new("target/debug/task-scheduler");
    command.arg("--addr").arg(format!("127.0.0.1:{}", port));

    // Add library directory option if provided
    if let Some(dir) = library_dir {
        command.arg("--library-dir").arg(dir);
    }

    let process = command
        .stdout(Stdio::inherit())
        .stderr(Stdio::inherit())
        .spawn()
        .expect("Failed to start server process");

    println!(
        "Test server process started (PID: {}, Port: {}).",
        process.id(),
        port
    );

    // Wait for server to start
    tokio::time::sleep(Duration::from_millis(700)).await;

    TestServer { process, port }
}
