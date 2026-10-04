use crate::error::TaskError;
use crate::scheduler::store::TaskSnapshot;
use crate::scheduler::Scheduler;
use crate::tasks::taskscheduler::task_response::Status as ProtoStatus;
use crate::tasks::taskscheduler::task_scheduler_server::TaskScheduler;
use crate::tasks::taskscheduler::{
    CancelRequest, CancelResponse, NodeInfo, NodeInfoRequest, ResultRequest, ResultResponse,
    TaskRequest, TaskResponse, WaitResultRequest,
};
use std::sync::Arc;
use std::time::Duration;
use tonic::{Code, Request, Response, Status};

/// gRPC adapter on top of the cluster-aware [`Scheduler`].
pub struct TaskSchedulerService {
    scheduler: Arc<Scheduler>,
}

impl TaskSchedulerService {
    pub fn new(scheduler: Arc<Scheduler>) -> Self {
        Self { scheduler }
    }

    fn to_status(error: TaskError) -> Status {
        let message = error.to_string();
        match error {
            TaskError::MethodNotFound(_) => Status::not_found(message),
            TaskError::InvalidArguments(_) => Status::invalid_argument(message),
            TaskError::MissingDependency(_) | TaskError::DependencyFailed(_) => {
                Status::failed_precondition(message)
            }
            TaskError::ExecutionError(_) => Status::internal(message),
            TaskError::Timeout(_) => Status::deadline_exceeded(message),
            TaskError::Cancelled(_) => Status::cancelled(message),
            TaskError::Remote { code, message } => Status::new(Code::from(code), message),
        }
    }

    fn to_result_response(snapshot: Option<TaskSnapshot>) -> ResultResponse {
        match snapshot {
            Some(snapshot) => ResultResponse {
                status: snapshot.status.to_proto() as i32,
                result: snapshot.result,
                node_id: snapshot.node_id,
            },
            None => ResultResponse {
                status: ProtoStatus::NotFound as i32,
                result: String::new(),
                node_id: String::new(),
            },
        }
    }
}

#[tonic::async_trait]
impl TaskScheduler for TaskSchedulerService {
    async fn submit_task(
        &self,
        request: Request<TaskRequest>,
    ) -> Result<Response<TaskResponse>, Status> {
        let task = request.into_inner();
        let task_id = task.task_id.clone();

        let snapshot = self.scheduler.submit(task).await.map_err(Self::to_status)?;

        Ok(Response::new(TaskResponse {
            task_id,
            status: snapshot.status.to_proto() as i32,
            result: snapshot.result,
            node_id: snapshot.node_id,
        }))
    }

    async fn get_result(
        &self,
        request: Request<ResultRequest>,
    ) -> Result<Response<ResultResponse>, Status> {
        let task_id = request.into_inner().task_id;
        let snapshot = self.scheduler.get_result(&task_id).await;
        Ok(Response::new(Self::to_result_response(snapshot)))
    }

    async fn wait_result(
        &self,
        request: Request<WaitResultRequest>,
    ) -> Result<Response<ResultResponse>, Status> {
        let request = request.into_inner();
        let snapshot = self
            .scheduler
            .wait_result(&request.task_id, Duration::from_millis(request.timeout_ms))
            .await;
        Ok(Response::new(Self::to_result_response(snapshot)))
    }

    async fn cancel_task(
        &self,
        request: Request<CancelRequest>,
    ) -> Result<Response<CancelResponse>, Status> {
        let task_id = request.into_inner().task_id;
        let (cancelled, status) = self.scheduler.cancel(&task_id).await;
        Ok(Response::new(CancelResponse {
            cancelled,
            status: status
                .map(|status| status.to_proto())
                .unwrap_or(ProtoStatus::NotFound) as i32,
        }))
    }

    async fn get_node_info(
        &self,
        _request: Request<NodeInfoRequest>,
    ) -> Result<Response<NodeInfo>, Status> {
        Ok(Response::new(self.scheduler.node_info()))
    }
}
