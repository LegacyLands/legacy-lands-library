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
        println!("Received task: {}.", task.task_id);

        let start = Instant::now();
        let result = REGISTRY.execute_task(&task).await;
        let duration = start.elapsed().as_millis();

        REGISTRY
            .cache_task_result(task.task_id.clone(), result.clone())
            .await;

        println!(
            "Completed task: {} with status {} (took {}ms).",
            task.task_id, result.status, duration
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
        if let Some(result) = REGISTRY.get_task_result(&req.task_id).await {
            Ok(Response::new(ResultResponse {
                is_ready: true,
                result: result.value,
            }))
        } else {
            Ok(Response::new(ResultResponse {
                is_ready: false,
                result: String::new(),
            }))
        }
    }
}
