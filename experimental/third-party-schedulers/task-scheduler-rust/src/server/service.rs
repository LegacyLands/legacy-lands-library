use crate::info_log;
use crate::tasks::taskscheduler::task_scheduler_server::TaskScheduler;
use crate::tasks::taskscheduler::{ResultRequest, ResultResponse, TaskRequest, TaskResponse};
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
        info_log!(
            "Received task: {} with method: {}, async: {}",
            task.task_id,
            task.method,
            task.is_async
        );

        let start = Instant::now();
        let result = REGISTRY.execute_task(&task).await;
        let duration = start.elapsed().as_millis();

        REGISTRY
            .cache_task_result(task.task_id.clone(), result.clone())
            .await;

        info_log!(
            "Completed task: {} with status {} (took {}ms). Result: {}",
            task.task_id,
            result.status,
            duration,
            result.value
        );

        Ok(Response::new(TaskResponse {
            task_id: task.task_id,
            status: result.status,
            result: result.value,
        }))
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
