use std::process::{Child, Command, Stdio};
use std::time::Duration;
use tokio::time::sleep;
// Removed tonic::transport::Channel as we don't connect here

pub struct TestServer {
    server_handle: Child, // We always start a server now
    pub port: u16,        // Store the dynamically picked port
}

impl TestServer {
    pub async fn start() -> Self {
        // Pick an unused port
        let port = portpicker::pick_unused_port().expect("No free ports available");
        // Consistently use IPv4 loopback
        let server_address_str = format!("127.0.0.1:{}", port);
        println!(
            "Selected unused port {}. Attempting to start server on {}...",
            port, server_address_str
        );

        // Determine the path to the target binary
        let target_dir = std::env::var("CARGO_TARGET_DIR").unwrap_or_else(|_| "target".to_string());
        // Assume debug profile for tests unless specified otherwise
        let profile = std::env::var("PROFILE").unwrap_or_else(|_| "debug".to_string());
        let exe_path = std::path::PathBuf::from(target_dir)
            .join(profile)
            .join("task-scheduler"); // Ensure this matches the binary name in Cargo.toml

        println!("Starting server binary from: {:?}", exe_path);

        let mut command = Command::new(exe_path);
        command
            .arg("--addr") // Pass the address using clap argument
            .arg(&server_address_str)
            .stdout(Stdio::piped()) // Capture stdout
            .stderr(Stdio::piped()); // Capture stderr

        let server_process = command.spawn().expect("Failed to start server process");

        let pid = server_process.id();
        println!(
            "Server process started (PID: {}), waiting for it to become ready on port {}...",
            pid, port
        );

        // Use the same IPv4 address for the check
        let bind_address = server_address_str.clone();

        for i in 1..=20 { // Increased attempts slightly
            // Increased attempts and delay
            sleep(Duration::from_millis(1000)).await;
            match tokio::net::TcpStream::connect(&bind_address).await {
                Ok(_) => {
                    println!(
                        "Server (PID: {}) is ready on port {} after {} attempts.",
                        pid, port, i
                    );
                    return TestServer {
                        server_handle: server_process,
                        port,
                    };
                }
                Err(e) => {
                    if i == 20 {
                        let mut owned_process = server_process;
                        // Attempt to read stdout/stderr before killing
                        let stdout = owned_process.stdout.take().map(|s| std::io::read_to_string(s));
                        let stderr = owned_process.stderr.take().map(|s| std::io::read_to_string(s));

                        owned_process
                            .kill()
                            .expect("Failed to kill server process after connection timeout.");
                        let _ = owned_process.wait(); // Wait for kill

                        eprintln!("--- Server stdout ---");
                        match stdout {
                            Some(Ok(out)) => eprintln!("{}", out),
                            Some(Err(io_err)) => eprintln!("Failed to read stdout: {}", io_err),
                            None => eprintln!("(not captured)"),
                        }
                        eprintln!("--- Server stderr ---");
                        match stderr {
                            Some(Ok(err)) => eprintln!("{}", err),
                            Some(Err(io_err)) => eprintln!("Failed to read stderr: {}", io_err),
                            None => eprintln!("(not captured)"),
                        }
                         eprintln!("-------------------");

                        panic!(
                            "Failed to connect to the server (PID: {}) on port {} after multiple attempts. Last error: {}",
                            pid, port, e
                        );
                    }
                    println!(
                        "Server (PID: {}) not ready on port {}, retrying... (attempt {}/20). Error: {}",
                        pid, port, i, e
                    );
                }
            }
        }
        unreachable!("Server startup loop finished unexpectedly.");
    }

    // Getter for the port
    pub fn address(&self) -> String {
        format!("http://127.0.0.1:{}", self.port)
    }
}

impl Drop for TestServer {
    fn drop(&mut self) {
        // Always try to kill the server process this instance started
        let pid = self.server_handle.id();
        println!(
            "Test finished. Killing server process (PID: {}, Port: {})...",
            pid, self.port
        );
        match self.server_handle.kill() {
            Ok(_) => {
                 // Read remaining output after kill, before wait
                let stdout_res = self.server_handle.stdout.take().map(|s| std::io::read_to_string(s));
                let stderr_res = self.server_handle.stderr.take().map(|s| std::io::read_to_string(s));

                match self.server_handle.wait() {
                    Ok(status) => println!(
                        "Server process (PID: {}, Port: {}) terminated successfully with status: {}",
                        pid, self.port, status
                    ),
                    Err(e) => eprintln!(
                        "Failed to wait for server process termination (PID: {}, Port: {}): {}",
                        pid, self.port, e
                    ),
                }
                 // Print captured output after waiting
                 if let Some(Ok(out)) = stdout_res { if !out.is_empty() { println!("Server stdout (on drop):\n{}", out); } }
                 if let Some(Ok(err)) = stderr_res { if !err.is_empty() { eprintln!("Server stderr (on drop):\n{}", err); } }
            },
            Err(e) => eprintln!(
                "Failed to kill server process (PID: {}, Port: {}): {}",
                pid, self.port, e
            ),
        }
    }
}

pub async fn setup() -> TestServer {
    TestServer::start().await
}
