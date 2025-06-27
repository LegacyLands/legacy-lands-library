use crate::reconciler::{ReconcileContext, TaskReconciler};
use futures::StreamExt;
use kube::{
    api::{Api, ResourceExt},
    runtime::{
        controller::{Action, Controller},
        finalizer::{finalizer, Event as FinalizerEvent},
        watcher::Config as WatcherConfig,
    },
    Client,
};
use std::sync::Arc;
use task_common::{
    crd::{Task, TaskPhase},
    error::{TaskError, TaskResult},
    events::TaskEvent,
    queue::QueueManager,
};
use tokio::time::Duration;
use tracing::{debug, error, info, instrument, warn};

/// Finalizer name for tasks
const TASK_FINALIZER: &str = "taskscheduler.legacylands.io/finalizer";

/// Controller for managing Task resources
pub struct TaskController {
    /// Kubernetes client
    client: Client,

    /// Namespace to watch
    namespace: String,

    /// Task reconciler
    _reconciler: Arc<TaskReconciler>,

    /// Queue manager
    queue: Arc<QueueManager>,
}

impl TaskController {
    /// Create a new task controller
    pub async fn new(
        client: Client,
        namespace: String,
        queue: Arc<QueueManager>,
    ) -> TaskResult<Self> {
        let reconciler = Arc::new(TaskReconciler::new(
            client.clone(),
            namespace.clone(),
            queue.clone(),
        ));

        Ok(Self {
            client,
            namespace,
            _reconciler: reconciler,
            queue,
        })
    }

    /// Run the controller
    pub async fn run(&self) -> TaskResult<()> {
        info!("Starting Task Controller for namespace {}", self.namespace);

        let api: Api<Task> = Api::namespaced(self.client.clone(), &self.namespace);

        // Create reconcile context
        let context = Arc::new(ReconcileContext {
            client: self.client.clone(),
            namespace: self.namespace.clone(),
            queue: self.queue.clone(),
        });

        // Set up the controller
        let controller = Controller::new(api.clone(), WatcherConfig::default())
            .shutdown_on_signal()
            .run(reconcile, error_policy, context)
            .for_each(|res| async move {
                match res {
                    Ok(o) => debug!("Reconciled: {:?}", o),
                    Err(e) => error!("Reconcile failed: {:?}", e),
                }
            });

        info!("Task Controller running");
        controller.await;

        info!("Task Controller stopped");
        Ok(())
    }
}

/// Main reconciliation logic
#[instrument(skip(ctx))]
async fn reconcile(task: Arc<Task>, ctx: Arc<ReconcileContext>) -> Result<Action, ReconcileError> {
    let name = task.name_any();
    info!("Reconciling Task {}", name);

    // Handle finalizer
    let api: Api<Task> = Api::namespaced(ctx.client.clone(), &ctx.namespace);

    match finalizer(&api, TASK_FINALIZER, task, |event| async {
        match event {
            FinalizerEvent::Apply(task) => {
                info!("Applying Task {}", task.name_any());
                apply(task, ctx.clone()).await
            }
            FinalizerEvent::Cleanup(task) => {
                info!("Cleaning up Task {}", task.name_any());
                cleanup(task, ctx.clone()).await
            }
        }
    })
    .await
    {
        Ok(action) => Ok(action),
        Err(e) => Err(ReconcileError::FinalizerError(e.to_string())),
    }
}

/// Apply task (create or update)
async fn apply(task: Arc<Task>, ctx: Arc<ReconcileContext>) -> Result<Action, ReconcileError> {
    let name = task.name_any();
    let reconciler =
        TaskReconciler::new(ctx.client.clone(), ctx.namespace.clone(), ctx.queue.clone());

    // Get current phase
    let current_phase = task
        .status
        .as_ref()
        .map(|s| s.phase.clone())
        .unwrap_or_default();

    debug!("Task {} is in phase {:?}", name, current_phase);

    // Reconcile based on current phase
    let action = match current_phase {
        TaskPhase::Pending => {
            // New task, validate and queue it
            reconciler.reconcile_pending(&task).await?
        }
        TaskPhase::Queued => {
            // Task is queued, check queue status
            reconciler.reconcile_queued(&task).await?
        }
        TaskPhase::Running => {
            // Task is running, check job status
            reconciler.reconcile_running(&task).await?
        }
        TaskPhase::Succeeded | TaskPhase::Failed | TaskPhase::Cancelled => {
            // Terminal state, ensure cleanup
            reconciler.reconcile_completed(&task).await?
        }
    };

    Ok(action)
}

/// Cleanup task (finalizer)
async fn cleanup(task: Arc<Task>, ctx: Arc<ReconcileContext>) -> Result<Action, ReconcileError> {
    let name = task.name_any();
    info!("Cleaning up resources for Task {}", name);

    let reconciler =
        TaskReconciler::new(ctx.client.clone(), ctx.namespace.clone(), ctx.queue.clone());

    // Clean up any associated resources
    reconciler.cleanup(&task).await?;

    // Send task cancelled event if not in terminal state
    let phase = task
        .status
        .as_ref()
        .map(|s| s.phase.clone())
        .unwrap_or_default();

    if !matches!(
        phase,
        TaskPhase::Succeeded | TaskPhase::Failed | TaskPhase::Cancelled
    ) {
        let event = TaskEvent::Cancelled {
            task_id: task.uid().unwrap_or_default().parse().unwrap_or_default(),
            reason: "Task resource deleted".to_string(),
            cancelled_by: Some("operator".to_string()),
            timestamp: chrono::Utc::now(),
        };

        if let Err(e) = ctx
            .queue
            .publish_event(event, "task-operator".to_string())
            .await
        {
            warn!("Failed to publish task cancelled event: {}", e);
        }
    }

    Ok(Action::await_change())
}

/// Error policy for the controller
fn error_policy(_task: Arc<Task>, error: &ReconcileError, _ctx: Arc<ReconcileContext>) -> Action {
    error!("Reconciliation error: {:?}", error);

    // Determine retry delay based on error type
    match error {
        ReconcileError::Temporary(_) => Action::requeue(Duration::from_secs(30)),
        ReconcileError::Permanent(_) => Action::requeue(Duration::from_secs(300)),
        _ => Action::requeue(Duration::from_secs(60)),
    }
}

/// Reconciliation errors
#[derive(Debug, thiserror::Error)]
pub enum ReconcileError {
    #[error("Temporary error: {0}")]
    Temporary(String),

    #[error("Permanent error: {0}")]
    Permanent(String),

    #[error("Kubernetes error: {0}")]
    Kubernetes(#[from] kube::Error),

    #[error("Task error: {0}")]
    Task(#[from] TaskError),

    #[error("Finalizer error: {0}")]
    FinalizerError(String),
}

impl From<kube::runtime::finalizer::Error<ReconcileError>> for ReconcileError {
    fn from(err: kube::runtime::finalizer::Error<ReconcileError>) -> Self {
        match err {
            kube::runtime::finalizer::Error::AddFinalizer(e) => {
                ReconcileError::FinalizerError(format!("Failed to add finalizer: {}", e))
            }
            kube::runtime::finalizer::Error::RemoveFinalizer(e) => {
                ReconcileError::FinalizerError(format!("Failed to remove finalizer: {}", e))
            }
            kube::runtime::finalizer::Error::ApplyFailed(e) => {
                ReconcileError::FinalizerError(format!("Apply failed: {:?}", e))
            }
            kube::runtime::finalizer::Error::CleanupFailed(e) => {
                ReconcileError::FinalizerError(format!("Cleanup failed: {:?}", e))
            }
            kube::runtime::finalizer::Error::UnnamedObject => {
                ReconcileError::FinalizerError("Unnamed object".to_string())
            }
            kube::runtime::finalizer::Error::InvalidFinalizer => {
                ReconcileError::FinalizerError("Invalid finalizer".to_string())
            }
        }
    }
}
