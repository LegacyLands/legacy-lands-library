use crate::error::TaskError;
use crate::error_log;
use crate::info_log;
use crate::tasks::taskscheduler::task_scheduler_server::TaskScheduler;
use crate::tasks::taskscheduler::{
    task_response, ResultRequest, ResultResponse, TaskRequest, TaskResponse,
};
use crate::tasks::REGISTRY;
use std::time::Instant;
use tonic::{Request, Response, Status};

#[derive(Debug, Default)]
pub struct TaskSchedulerService {}

#[tonic::async_trait]
impl TaskScheduler for TaskSchedulerService {
    async fn submit_task(
        &self,
        request: Request<TaskRequest>,
    ) -> Result<Response<TaskResponse>, Status> {
        let task = request.into_inner();
        let task_id = task.task_id.clone();
        let method = task.method.clone();
        let is_async = task.is_async;

        info_log!(
            "Received task: {} with method: {}, async: {}",
            task_id,
            method,
            is_async
        );

        let start = Instant::now();
        let execution_result = REGISTRY.execute_task(&task).await;
        let duration = start.elapsed().as_millis();

        match execution_result {
            Ok(value) => {
                info_log!(
                    "Completed task: {} successfully (took {}ms). Result: {}",
                    task_id,
                    duration,
                    value
                );
                Ok(Response::new(TaskResponse {
                    task_id,
                    status: task_response::Status::Success as i32,
                    result: value,
                }))
            }
            Err(err) => {
                error_log!(
                    "Failed task: {} (took {}ms). Error: {}",
                    task_id,
                    duration,
                    err
                );
                let status = match err {
                    TaskError::MethodNotFound(m) => {
                        Status::not_found(format!("Method not found: {}", m))
                    }
                    TaskError::InvalidArguments(a) => {
                        Status::invalid_argument(format!("Invalid arguments: {}", a))
                    }
                    TaskError::MissingDependency(d) => {
                        Status::failed_precondition(format!("Missing dependency: {}", d))
                    }
                    TaskError::ExecutionError(e) => {
                        Status::internal(format!("Task execution failed: {}", e))
                    }
                };
                Err(status)
            }
        }
    }

    async fn get_result(
        &self,
        request: Request<ResultRequest>,
    ) -> Result<Response<ResultResponse>, Status> {
        let req = request.into_inner();
        if let Some(cached_result) = REGISTRY.get_task_result(&req.task_id).await {
            Ok(Response::new(ResultResponse {
                status: cached_result.status,
                result: cached_result.value,
            }))
        } else {
            Ok(Response::new(ResultResponse {
                status: crate::tasks::taskscheduler::task_response::Status::Pending as i32,
                result: String::new(),
            }))
        }
    }
}
