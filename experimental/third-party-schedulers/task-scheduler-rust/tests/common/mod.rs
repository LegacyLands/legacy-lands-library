use portpicker;
use std::process::{Child, Command, Stdio};
use std::time::Duration;

pub struct TestServer {
    process: Child,
    port: u16,
}

impl TestServer {
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

/// Sets up a *new* test server instance on a free port for each call.
/// Returns a TestServer struct containing the server address and process handle.
#[allow(clippy::zombie_processes)]
pub async fn setup() -> TestServer {
    println!("Ensuring test server binary is built...");
    assert!(
        Command::new("cargo")
            .args(["build", "--bin", "task-scheduler"])
            .status()
            .expect("Failed to build server binary")
            .success(),
        "Failed to build the server binary"
    );

    let port = portpicker::pick_unused_port().expect("Failed to find an unused port");
    println!("Selected port {} for test server.", port);

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

    tokio::time::sleep(Duration::from_millis(500)).await;

    TestServer { process, port }
}
