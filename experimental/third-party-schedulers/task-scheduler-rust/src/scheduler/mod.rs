pub mod store;

use crate::cluster::{remote_error, Cluster, Peer};
use crate::error::TaskError;
use crate::models::ArgValue;
use crate::tasks::taskscheduler::task_response::Status as ProtoStatus;
use crate::tasks::taskscheduler::{NodeInfo, ResultResponse, TaskRequest};
use crate::tasks::{TaskRegistry, REGISTRY};
use crate::{debug_log, error_log, info_log, warn_log};
use futures::FutureExt;
use std::panic::AssertUnwindSafe;
use std::sync::Arc;
use std::time::{Duration, Instant};
use store::{TaskLocation, TaskRecord, TaskSnapshot, TaskStatus, TaskStore};
use tokio::sync::Semaphore;

/// Consecutive failed RPCs tolerated while following a task on a peer.
const MAX_PEER_FAILURES: u32 = 10;

/// Runtime configuration of a scheduler node.
#[derive(Clone)]
pub struct SchedulerConfig {
    pub node_id: String,
    /// Used when a request does not specify `dependency_timeout_ms`.
    pub default_dependency_timeout: Duration,
    /// Upper bound of a single `WaitResult` call.
    pub max_wait_timeout: Duration,
    /// Maximum number of concurrently executing tasks, `0` means unlimited.
    pub max_concurrent_tasks: usize,
    /// How long finished results are kept for queries and dependency resolution.
    pub result_ttl: Duration,
}

impl Default for SchedulerConfig {
    fn default() -> Self {
        Self {
            node_id: "local".to_string(),
            default_dependency_timeout: Duration::from_secs(30),
            max_wait_timeout: Duration::from_secs(30),
            max_concurrent_tasks: 0,
            result_ttl: Duration::from_secs(600),
        }
    }
}

/// Execution parameters extracted from a request.
struct Execution {
    method: String,
    args: Vec<ArgValue>,
    deps: Vec<String>,
    inject_dependency_results: bool,
    dependency_timeout: Duration,
    execution_timeout: Option<Duration>,
}

/// Cluster-aware task scheduler of a single node.
///
/// Tasks are executed locally when the method is registered here, otherwise they are forwarded
/// to a peer providing the method. Dependencies are resolved across the whole cluster:
/// a dependency that is unknown locally is located on the peers and followed by long polling,
/// and dependencies submitted later than their dependents are awaited until the timeout.
pub struct Scheduler {
    config: SchedulerConfig,
    store: TaskStore,
    cluster: Arc<Cluster>,
    permits: Option<Arc<Semaphore>>,
}

impl Scheduler {
    pub fn new(config: SchedulerConfig, cluster: Arc<Cluster>) -> Self {
        let permits = (config.max_concurrent_tasks > 0)
            .then(|| Arc::new(Semaphore::new(config.max_concurrent_tasks)));
        Self {
            config,
            store: TaskStore::default(),
            cluster,
            permits,
        }
    }

    pub fn node_id(&self) -> &str {
        &self.config.node_id
    }

    /// Starts the cluster heartbeat and the expired result sweeper.
    pub fn start_background_tasks(self: &Arc<Self>) {
        self.cluster.start_heartbeat(self.node_id());

        let scheduler = Arc::clone(self);
        let period =
            (self.config.result_ttl / 2).clamp(Duration::from_secs(1), Duration::from_secs(30));
        tokio::spawn(async move {
            let mut interval = tokio::time::interval(period);
            loop {
                interval.tick().await;
                let evicted = scheduler.store.evict_expired(scheduler.config.result_ttl);
                if evicted > 0 {
                    debug_log!(
                        "Evicted {} expired task records, {} remaining",
                        evicted,
                        scheduler.store.len()
                    );
                }
            }
        });
    }

    /// Submits a task. Detached submissions return the current (usually pending) snapshot,
    /// blocking submissions return once the task is terminal.
    ///
    /// Submitting an id that is already known is idempotent: the existing task is reused
    /// instead of executing again, which makes client retries safe.
    pub async fn submit(self: &Arc<Self>, request: TaskRequest) -> Result<TaskSnapshot, TaskError> {
        Self::validate(&request)?;

        if let Some(existing) = self.store.get(&request.task_id) {
            info_log!(
                "Task '{}' is already known on this node, reusing its record",
                request.task_id
            );
            return self.follow(existing, request.detached).await;
        }

        if !REGISTRY.contains(&request.method) {
            return self.forward(request).await;
        }

        let args = TaskRegistry::convert_args(&request.args)?;
        let record = Arc::new(TaskRecord::new(
            &request.task_id,
            &request.method,
            self.node_id(),
            TaskLocation::Local,
        ));
        let (record, inserted) = self.store.insert_if_absent(record);
        if !inserted {
            return self.follow(record, request.detached).await;
        }

        info_log!(
            "Accepted task '{}' (method: {}, deps: {:?}, detached: {})",
            request.task_id,
            request.method,
            request.deps,
            request.detached
        );

        let detached = request.detached;
        let execution = Execution {
            method: request.method,
            args,
            deps: request.deps,
            inject_dependency_results: request.inject_dependency_results,
            dependency_timeout: match request.dependency_timeout_ms {
                0 => self.config.default_dependency_timeout,
                millis => Duration::from_millis(millis),
            },
            execution_timeout: (request.execution_timeout_ms > 0)
                .then(|| Duration::from_millis(request.execution_timeout_ms)),
        };

        let scheduler = Arc::clone(self);
        let driver_record = Arc::clone(&record);
        let handle = tokio::spawn(async move { scheduler.drive(driver_record, execution).await });
        record.set_abort_handle(handle.abort_handle());

        self.follow(record, detached).await
    }

    fn validate(request: &TaskRequest) -> Result<(), TaskError> {
        if request.task_id.trim().is_empty() {
            return Err(TaskError::InvalidArguments(
                "Task id must not be empty".to_string(),
            ));
        }
        if request.method.trim().is_empty() {
            return Err(TaskError::InvalidArguments(
                "Method must not be empty".to_string(),
            ));
        }
        if request.deps.iter().any(|dep| dep == &request.task_id) {
            return Err(TaskError::InvalidArguments(format!(
                "Task '{}' cannot depend on itself",
                request.task_id
            )));
        }
        Ok(())
    }

    /// Returns the record's snapshot right away (detached) or once it is terminal.
    async fn follow(
        &self,
        record: Arc<TaskRecord>,
        detached: bool,
    ) -> Result<TaskSnapshot, TaskError> {
        if detached {
            return Ok(self.current_snapshot(&record).await);
        }

        let snapshot = match &record.location {
            TaskLocation::Local => record.wait_terminal(None).await,
            TaskLocation::Remote { address } => {
                let peer = self.peer_by_address(address)?;
                self.follow_remote(&peer, &record.task_id).await?
            }
        };
        snapshot.into_result()
    }

    /// Drives a local task through dependency resolution and execution, recording the outcome.
    async fn drive(self: Arc<Self>, record: Arc<TaskRecord>, execution: Execution) {
        let start = Instant::now();
        let outcome = AssertUnwindSafe(self.run(&record, execution))
            .catch_unwind()
            .await
            .unwrap_or_else(|_| Err(TaskError::ExecutionError("Task panicked".to_string())));
        let elapsed = start.elapsed().as_millis();

        match outcome {
            Ok(value) => {
                if record.finish(TaskStatus::Success, value.clone(), None) {
                    info_log!(
                        "Completed task '{}' successfully (took {}ms). Result: {}",
                        record.task_id,
                        elapsed,
                        value
                    );
                }
            }
            Err(error) => {
                let status = match error {
                    TaskError::Cancelled(_) => TaskStatus::Cancelled,
                    _ => TaskStatus::Failed,
                };
                if record.finish(status, error.to_string(), Some(error.clone())) {
                    error_log!(
                        "Failed task '{}' (took {}ms). Error: {}",
                        record.task_id,
                        elapsed,
                        error
                    );
                }
            }
        }
    }

    async fn run(&self, record: &TaskRecord, execution: Execution) -> Result<String, TaskError> {
        let Execution {
            method,
            mut args,
            deps,
            inject_dependency_results,
            dependency_timeout,
            execution_timeout,
        } = execution;

        if !deps.is_empty() {
            let deadline = Instant::now() + dependency_timeout;
            let resolved = futures::future::try_join_all(
                deps.iter().map(|dep| self.await_dependency(dep, deadline)),
            )
            .await?;

            debug_log!(
                "Dependencies of task '{}' resolved: {:?}",
                record.task_id,
                deps
            );

            if inject_dependency_results {
                let injected = resolved
                    .into_iter()
                    .map(|snapshot| ArgValue::String(snapshot.result));
                args = injected.chain(args).collect();
            }
        }

        let _permit = match &self.permits {
            Some(permits) => Some(Arc::clone(permits).acquire_owned().await.map_err(|_| {
                TaskError::ExecutionError("Scheduler is shutting down".to_string())
            })?),
            None => None,
        };

        if !record.mark_running() {
            return Err(TaskError::Cancelled(format!(
                "Task '{}' was cancelled before execution",
                record.task_id
            )));
        }

        // Resolve at execution time so unloaded dynamic plugins are never invoked
        let handler = REGISTRY
            .resolve(&method)
            .ok_or_else(|| TaskError::MethodNotFound(method.clone()))?;

        match execution_timeout {
            Some(timeout) => tokio::time::timeout(timeout, handler.invoke(args))
                .await
                .map_err(|_| {
                    TaskError::Timeout(format!(
                        "Task '{}' exceeded execution timeout of {}ms",
                        record.task_id,
                        timeout.as_millis()
                    ))
                })?,
            None => handler.invoke(args).await,
        }
    }

    /// Waits for a dependency that may live on this node or any peer, until `deadline`.
    async fn await_dependency(
        &self,
        dep_id: &str,
        deadline: Instant,
    ) -> Result<TaskSnapshot, TaskError> {
        let mut backoff = Duration::from_millis(50);

        loop {
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                return Err(TaskError::Timeout(format!(
                    "Dependency '{}' did not complete in time",
                    dep_id
                )));
            }
            let window = remaining.min(self.config.max_wait_timeout);

            // Bound every lookup by the remaining time, so slow or hanging peers cannot push past the deadline
            let lookup = async {
                if let Some(record) = self.store.get(dep_id) {
                    return match &record.location {
                        TaskLocation::Local => Some(record.wait_terminal(Some(window)).await),
                        TaskLocation::Remote { address } => match self.cluster.find_peer(address) {
                            Some(peer) => self.wait_on_peer(&peer, dep_id, window).await,
                            None => None,
                        },
                    };
                }

                let (peer, response) = self.cluster.locate(dep_id).await?;
                debug_log!("Located dependency '{}' on peer '{}'", dep_id, peer.label());
                match Self::snapshot_from_response(response) {
                    Some(snapshot) if snapshot.status.is_terminal() => Some(snapshot),
                    _ => self.wait_on_peer(&peer, dep_id, window).await,
                }
            };
            let snapshot = tokio::time::timeout(remaining, lookup)
                .await
                .unwrap_or(None);

            match snapshot {
                Some(snapshot) if snapshot.status.is_terminal() => {
                    return Self::check_dependency(dep_id, snapshot);
                }
                // Still running somewhere, the next iteration keeps waiting
                Some(_) => {}
                // Unknown in the whole cluster (yet) or peer unreachable, back off and retry
                None => {
                    tokio::time::sleep(backoff.min(remaining)).await;
                    backoff = (backoff * 2).min(Duration::from_secs(1));
                }
            }
        }
    }

    fn check_dependency(dep_id: &str, snapshot: TaskSnapshot) -> Result<TaskSnapshot, TaskError> {
        match snapshot.status {
            TaskStatus::Success => Ok(snapshot),
            status => Err(TaskError::DependencyFailed(format!(
                "Dependency '{}' on node '{}' ended with {:?}: {}",
                dep_id, snapshot.node_id, status, snapshot.result
            ))),
        }
    }

    async fn wait_on_peer(
        &self,
        peer: &Peer,
        task_id: &str,
        timeout: Duration,
    ) -> Option<TaskSnapshot> {
        match self.cluster.wait_remote(peer, task_id, timeout).await {
            Ok(response) => Self::snapshot_from_response(response),
            Err(status) => {
                warn_log!(
                    "Failed to wait for task '{}' on peer '{}': {}",
                    task_id,
                    peer.label(),
                    status.message()
                );
                None
            }
        }
    }

    /// Follows a task on a peer until it is terminal, tolerating transient peer failures.
    async fn follow_remote(&self, peer: &Peer, task_id: &str) -> Result<TaskSnapshot, TaskError> {
        let mut failures = 0;
        loop {
            match self
                .wait_on_peer(peer, task_id, self.config.max_wait_timeout)
                .await
            {
                Some(snapshot) if snapshot.status.is_terminal() => return Ok(snapshot),
                Some(_) => failures = 0,
                None => {
                    failures += 1;
                    if failures >= MAX_PEER_FAILURES {
                        return Err(TaskError::Remote {
                            code: tonic::Code::Unavailable as i32,
                            message: format!(
                                "Lost track of task '{}' on peer '{}'",
                                task_id,
                                peer.label()
                            ),
                        });
                    }
                    tokio::time::sleep(Duration::from_millis(500)).await;
                }
            }
        }
    }

    /// Forwards a task whose method is not registered locally to the least loaded capable peer.
    async fn forward(&self, request: TaskRequest) -> Result<TaskSnapshot, TaskError> {
        let method_not_found = || TaskError::MethodNotFound(request.method.clone());

        // Forwarded requests are never forwarded again, which rules out loops
        if request.disable_forwarding || !request.origin_node_id.is_empty() {
            return Err(method_not_found());
        }
        let peer = self
            .cluster
            .select_peer_for(&request.method)
            .ok_or_else(method_not_found)?;

        let record = Arc::new(TaskRecord::new(
            &request.task_id,
            &request.method,
            &peer.label(),
            TaskLocation::Remote {
                address: peer.address.clone(),
            },
        ));
        let (record, inserted) = self.store.insert_if_absent(record);
        if !inserted {
            return self.follow(record, request.detached).await;
        }

        info_log!(
            "Forwarding task '{}' (method: {}) to peer '{}'",
            request.task_id,
            request.method,
            peer.label()
        );

        let forwarded = TaskRequest {
            origin_node_id: self.node_id().to_string(),
            ..request
        };
        let response = match self.cluster.submit_remote(&peer, forwarded).await {
            Ok(response) => response,
            Err(status) => {
                // Keep the record only if the peer accepted the task (e.g. it ran and failed),
                // otherwise drop it so that a later resubmission is forwarded again
                let accepted = self
                    .cluster
                    .get_remote(&peer, &record.task_id)
                    .await
                    .is_ok_and(|remote| remote.status != ProtoStatus::NotFound as i32);
                if !accepted {
                    self.store.remove(&record);
                    warn_log!(
                        "Forwarding task '{}' to peer '{}' failed: {}",
                        record.task_id,
                        peer.label(),
                        status.message()
                    );
                }
                return Err(remote_error(status));
            }
        };

        if !response.node_id.is_empty() {
            record.set_node_id(&response.node_id);
        }

        Ok(TaskSnapshot {
            status: TaskStatus::from_proto(response.status).unwrap_or(TaskStatus::Pending),
            result: response.result,
            error: None,
            node_id: response.node_id,
        })
    }

    /// Returns the current state of a task known by this node, `None` if unknown.
    pub async fn get_result(&self, task_id: &str) -> Option<TaskSnapshot> {
        let record = self.store.get(task_id)?;
        Some(self.current_snapshot(&record).await)
    }

    /// Waits up to `timeout` (capped by the configuration) for a task known by this node.
    pub async fn wait_result(&self, task_id: &str, timeout: Duration) -> Option<TaskSnapshot> {
        let record = self.store.get(task_id)?;
        let timeout = match timeout.is_zero() {
            true => self.config.max_wait_timeout,
            false => timeout.min(self.config.max_wait_timeout),
        };

        match &record.location {
            TaskLocation::Local => Some(record.wait_terminal(Some(timeout)).await),
            TaskLocation::Remote { address } => match self.cluster.find_peer(address) {
                Some(peer) => self.wait_on_peer(&peer, task_id, timeout).await,
                None => Some(record.snapshot()),
            },
        }
    }

    async fn current_snapshot(&self, record: &TaskRecord) -> TaskSnapshot {
        match &record.location {
            TaskLocation::Local => record.snapshot(),
            TaskLocation::Remote { address } => {
                let remote = match self.cluster.find_peer(address) {
                    Some(peer) => self.cluster.get_remote(&peer, &record.task_id).await.ok(),
                    None => None,
                };
                remote
                    .and_then(Self::snapshot_from_response)
                    .unwrap_or_else(|| record.snapshot())
            }
        }
    }

    /// Cancels a non-terminal task on this node, or on the peer that owns it.
    /// Returns whether the task was cancelled and its resulting status (`None` if unknown).
    ///
    /// Running sync tasks cannot be interrupted, their result is discarded instead.
    pub async fn cancel(&self, task_id: &str) -> (bool, Option<TaskStatus>) {
        let owner = match self.store.get(task_id) {
            Some(record) => match &record.location {
                TaskLocation::Local => {
                    let cancelled = record.finish(
                        TaskStatus::Cancelled,
                        "Cancelled by request".to_string(),
                        Some(TaskError::Cancelled(format!(
                            "Task '{}' was cancelled",
                            task_id
                        ))),
                    );
                    if cancelled {
                        record.abort();
                        info_log!("Cancelled task '{}'", task_id);
                    }
                    return (cancelled, Some(record.snapshot().status));
                }
                TaskLocation::Remote { address } => self.cluster.find_peer(address),
            },
            None => self.cluster.locate(task_id).await.map(|(peer, _)| peer),
        };

        let Some(peer) = owner else {
            return (false, None);
        };
        match self.cluster.cancel_remote(&peer, task_id).await {
            Ok(response) => (response.cancelled, TaskStatus::from_proto(response.status)),
            Err(status) => {
                warn_log!(
                    "Failed to cancel task '{}' on peer '{}': {}",
                    task_id,
                    peer.label(),
                    status.message()
                );
                (false, None)
            }
        }
    }

    pub fn node_info(&self) -> NodeInfo {
        let (pending, running) = self.store.local_load();
        NodeInfo {
            node_id: self.node_id().to_string(),
            methods: REGISTRY.method_names(),
            pending_tasks: pending,
            running_tasks: running,
            peers: self.cluster.peer_infos(),
        }
    }

    fn peer_by_address(&self, address: &str) -> Result<Arc<Peer>, TaskError> {
        self.cluster
            .find_peer(address)
            .ok_or_else(|| TaskError::Remote {
                code: tonic::Code::Unavailable as i32,
                message: format!("Unknown peer '{}'", address),
            })
    }

    fn snapshot_from_response(response: ResultResponse) -> Option<TaskSnapshot> {
        Some(TaskSnapshot {
            status: TaskStatus::from_proto(response.status)?,
            result: response.result,
            error: None,
            node_id: response.node_id,
        })
    }
}
