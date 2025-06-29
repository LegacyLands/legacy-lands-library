use task_common::proto::{
    TaskRequest, TaskResponse, ResultRequest, ResultResponse
};
use task_common::proto::taskscheduler::task_scheduler_client::TaskSchedulerClient;
use prost_types::Any;
use tonic::transport::Channel;

pub struct TestEnvironment {
    manager_endpoint: String,
    #[allow(dead_code)]
    nats_url: String,
    #[allow(dead_code)]
    manager_metrics_port: u16,
}

impl TestEnvironment {
    pub async fn new() -> Result<Self, Box<dyn std::error::Error + Send + Sync>> {
        // In CI/docker environment, use service names
        // In local development, use localhost
        let is_ci = std::env::var("CI").is_ok();
        
        let manager_endpoint = if is_ci {
            "http://task-manager:50051".to_string()
        } else {
            std::env::var("GRPC_ADDRESS")
                .unwrap_or_else(|_| "http://localhost:50052".to_string())
        };
        
        let nats_url = if is_ci {
            "nats://nats:4222".to_string()
        } else {
            std::env::var("NATS_URL")
                .unwrap_or_else(|_| "nats://localhost:4222".to_string())
        };
        
        let manager_metrics_port = 9091;
        
        Ok(Self {
            manager_endpoint,
            nats_url,
            manager_metrics_port,
        })
    }
    
    pub async fn create_grpc_client(&self) -> Result<TestClient, Box<dyn std::error::Error + Send + Sync>> {
        let client = TaskSchedulerClient::connect(self.manager_endpoint.clone()).await?;
        Ok(TestClient { client })
    }
    
    #[allow(dead_code)]
    pub fn manager_metrics_url(&self) -> String {
        let host = if std::env::var("CI").is_ok() {
            "task-manager"
        } else {
            "localhost"
        };
        format!("http://{}:{}", host, self.manager_metrics_port)
    }
}

#[derive(Clone)]
pub struct TestClient {
    pub client: TaskSchedulerClient<Channel>,
}

impl TestClient {
    #[allow(dead_code)]
    pub async fn submit_echo_task(&mut self, message: &str) -> Result<TaskResponse, Box<dyn std::error::Error + Send + Sync>> {
        let task_id = uuid::Uuid::new_v4();
        let request = TaskRequest {
            task_id: task_id.to_string(),
            method: "echo".to_string(),
            args: vec![Any {
                type_url: "type.googleapis.com/google.protobuf.StringValue".to_string(),
                value: serde_json::to_vec(&message).unwrap(),
            }],
            deps: vec![],
            is_async: false,  // Keep synchronous for simple echo tasks
        };
        
        let response = self.client.submit_task(request).await?;
        Ok(response.into_inner())
    }
    
    #[allow(dead_code)]
    pub async fn submit_async_sleep_task(&mut self, seconds: u32) -> Result<String, Box<dyn std::error::Error + Send + Sync>> {
        let request = TaskRequest {
            task_id: uuid::Uuid::new_v4().to_string(),
            method: "sleep".to_string(),
            args: vec![Any {
                type_url: "type.googleapis.com/google.protobuf.Int32Value".to_string(),
                value: serde_json::to_vec(&seconds).unwrap(),
            }],
            deps: vec![],
            is_async: true,
        };
        
        let response = self.client.submit_task(request).await?;
        Ok(response.into_inner().task_id)
    }
    
    #[allow(dead_code)]
    pub async fn submit_computation_task(&mut self, a: i32, b: i32) -> Result<String, Box<dyn std::error::Error + Send + Sync>> {
        let request = TaskRequest {
            task_id: uuid::Uuid::new_v4().to_string(),
            method: "add".to_string(),
            args: vec![
                Any {
                    type_url: "type.googleapis.com/google.protobuf.Int32Value".to_string(),
                    value: serde_json::to_vec(&a).unwrap(),
                },
                Any {
                    type_url: "type.googleapis.com/google.protobuf.Int32Value".to_string(),
                    value: serde_json::to_vec(&b).unwrap(),
                },
            ],
            deps: vec![],
            is_async: true,
        };
        
        let response = self.client.submit_task(request).await?;
        Ok(response.into_inner().task_id)
    }
    
    #[allow(dead_code)]
    pub async fn submit_async_computation_task(&mut self, a: i32, b: i32) -> Result<String, Box<dyn std::error::Error + Send + Sync>> {
        self.submit_computation_task(a, b).await
    }
    
    #[allow(dead_code)]
    pub async fn submit_dependent_task(&mut self, dep_task_id: &str, multiplier: i32) -> Result<String, Box<dyn std::error::Error + Send + Sync>> {
        let request = TaskRequest {
            task_id: uuid::Uuid::new_v4().to_string(),
            method: "multiply".to_string(),
            args: vec![
                Any {
                    type_url: "type.googleapis.com/google.protobuf.StringValue".to_string(),
                    value: serde_json::to_vec(&format!("@{}", dep_task_id)).unwrap(),
                },
                Any {
                    type_url: "type.googleapis.com/google.protobuf.Int32Value".to_string(),
                    value: serde_json::to_vec(&multiplier).unwrap(),
                },
            ],
            deps: vec![dep_task_id.to_string()],
            is_async: true,
        };
        
        let response = self.client.submit_task(request).await?;
        Ok(response.into_inner().task_id)
    }
    
    #[allow(dead_code)]
    pub async fn submit_invalid_task(&mut self) -> Result<TaskResponse, Box<dyn std::error::Error + Send + Sync>> {
        let request = TaskRequest {
            task_id: uuid::Uuid::new_v4().to_string(),
            method: "non_existent_method".to_string(),
            args: vec![],
            deps: vec![],
            is_async: true,
        };
        
        let response = self.client.submit_task(request).await?;
        Ok(response.into_inner())
    }
    
    #[allow(dead_code)]
    pub async fn get_task_result(&mut self, task_id: &str) -> Result<ResultResponse, Box<dyn std::error::Error + Send + Sync>> {
        let request = ResultRequest {
            task_id: task_id.to_string(),
        };
        
        let response = self.client.get_result(request).await?;
        Ok(response.into_inner())
    }
}