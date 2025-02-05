use std::process::Command;
use std::time::Duration;
use tokio::time::sleep;
use tonic::transport::Channel;

pub struct TestServer {
    server_handle: Option<std::process::Child>,
}

impl TestServer {
    pub async fn start() -> Self {
        let server = Command::new("cargo")
            .args(["run", "--bin", "task-scheduler"])
            .spawn()
            .expect("Failed to start server");

        println!("Server process started, waiting for it to be ready...");

        for i in 1..=10 {
            sleep(Duration::from_millis(1000)).await;
            
            match Channel::from_static("http://[::1]:50051").connect().await {
                Ok(_) => {
                    println!("Server is ready after {} attempts.", i);
                    return TestServer {
                        server_handle: Some(server),
                    };
                }
                Err(_) => {
                    if i == 10 {
                        if let Some(mut server) = Some(server) {
                            server.kill().expect("Failed to kill server.");
                        }
                        panic!("Failed to connect to server after multiple attempts.");
                    }
                    println!("Server not ready, retrying... (attempt {}/10).", i);
                }
            }
        }

        TestServer {
            server_handle: Some(server),
        }
    }
}

impl Drop for TestServer {
    fn drop(&mut self) {
        if let Some(mut server) = self.server_handle.take() {
            server.kill().expect("Failed to kill server");
        }
    }
}

pub async fn setup() -> TestServer {
    TestServer::start().await
}
