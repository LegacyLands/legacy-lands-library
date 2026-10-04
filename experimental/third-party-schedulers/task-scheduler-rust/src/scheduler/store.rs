use crate::error::TaskError;
use crate::tasks::taskscheduler::task_response::Status;
use dashmap::mapref::entry::Entry;
use dashmap::DashMap;
use parking_lot::Mutex;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::sync::watch;
use tokio::task::AbortHandle;

/// Lifecycle state of a task.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TaskStatus {
    /// Accepted, waiting for dependencies or a free execution slot.
    Pending,
    Running,
    Success,
    Failed,
    Cancelled,
}

impl TaskStatus {
    pub fn is_terminal(self) -> bool {
        matches!(
            self,
            TaskStatus::Success | TaskStatus::Failed | TaskStatus::Cancelled
        )
    }

    pub fn to_proto(self) -> Status {
        match self {
            TaskStatus::Pending => Status::Pending,
            TaskStatus::Running => Status::Running,
            TaskStatus::Success => Status::Success,
            TaskStatus::Failed => Status::Failed,
            TaskStatus::Cancelled => Status::Cancelled,
        }
    }

    /// Maps a proto status, returning `None` for `NOT_FOUND` or unknown values.
    pub fn from_proto(status: i32) -> Option<Self> {
        match Status::try_from(status).ok()? {
            Status::Pending => Some(TaskStatus::Pending),
            Status::Running => Some(TaskStatus::Running),
            Status::Success => Some(TaskStatus::Success),
            Status::Failed => Some(TaskStatus::Failed),
            Status::Cancelled => Some(TaskStatus::Cancelled),
            Status::NotFound => None,
        }
    }
}

/// Point-in-time view of a task.
#[derive(Debug, Clone)]
pub struct TaskSnapshot {
    pub status: TaskStatus,
    /// Task output on success, error message on failure.
    pub result: String,
    /// Structured error for failed or cancelled tasks.
    pub error: Option<TaskError>,
    /// Id of the node that owns the task.
    pub node_id: String,
}

impl TaskSnapshot {
    pub fn pending(node_id: &str) -> Self {
        Self {
            status: TaskStatus::Pending,
            result: String::new(),
            error: None,
            node_id: node_id.to_string(),
        }
    }

    /// Converts a finished snapshot into its result, using the stored error for unsuccessful tasks.
    pub fn into_result(self) -> Result<TaskSnapshot, TaskError> {
        match self.status {
            TaskStatus::Success | TaskStatus::Pending | TaskStatus::Running => Ok(self),
            TaskStatus::Failed => Err(self.error.unwrap_or(TaskError::ExecutionError(self.result))),
            TaskStatus::Cancelled => Err(self.error.unwrap_or(TaskError::Cancelled(self.result))),
        }
    }
}

/// Where a task is actually executed.
#[derive(Debug, Clone)]
pub enum TaskLocation {
    Local,
    /// The task was forwarded to the peer reachable at `address`.
    Remote {
        address: String,
    },
}

pub struct TaskRecord {
    pub task_id: String,
    pub method: String,
    pub location: TaskLocation,
    created_at: Instant,
    state: watch::Sender<TaskSnapshot>,
    abort_handle: Mutex<Option<AbortHandle>>,
    finished_at: Mutex<Option<Instant>>,
}

impl TaskRecord {
    pub fn new(task_id: &str, method: &str, node_id: &str, location: TaskLocation) -> Self {
        let (state, _) = watch::channel(TaskSnapshot::pending(node_id));
        Self {
            task_id: task_id.to_string(),
            method: method.to_string(),
            location,
            created_at: Instant::now(),
            state,
            abort_handle: Mutex::new(None),
            finished_at: Mutex::new(None),
        }
    }

    pub fn is_local(&self) -> bool {
        matches!(self.location, TaskLocation::Local)
    }

    pub fn snapshot(&self) -> TaskSnapshot {
        self.state.borrow().clone()
    }

    /// Moves a pending task to running. Returns `false` if the task is no longer pending.
    pub fn mark_running(&self) -> bool {
        self.state.send_if_modified(|snapshot| {
            if snapshot.status != TaskStatus::Pending {
                return false;
            }
            snapshot.status = TaskStatus::Running;
            true
        })
    }

    /// Moves the task into a terminal state. Returns `false` if it already was terminal,
    /// which makes completion and cancellation race-free.
    pub fn finish(&self, status: TaskStatus, result: String, error: Option<TaskError>) -> bool {
        debug_assert!(status.is_terminal());
        let changed = self.state.send_if_modified(|snapshot| {
            if snapshot.status.is_terminal() {
                return false;
            }
            snapshot.status = status;
            snapshot.result = result;
            snapshot.error = error;
            true
        });
        if changed {
            *self.finished_at.lock() = Some(Instant::now());
        }
        changed
    }

    /// Updates the owner node id, used once a forwarded task is accepted by a peer.
    pub fn set_node_id(&self, node_id: &str) {
        self.state
            .send_modify(|snapshot| snapshot.node_id = node_id.to_string());
    }

    /// Waits until the task is terminal or the timeout elapses (`None` waits forever),
    /// then returns the latest snapshot.
    pub async fn wait_terminal(&self, timeout: Option<Duration>) -> TaskSnapshot {
        let mut receiver = self.state.subscribe();
        let waiting = receiver.wait_for(|snapshot| snapshot.status.is_terminal());
        match timeout {
            Some(duration) => {
                let _ = tokio::time::timeout(duration, waiting).await;
            }
            None => {
                let _ = waiting.await;
            }
        }
        self.snapshot()
    }

    pub fn set_abort_handle(&self, handle: AbortHandle) {
        *self.abort_handle.lock() = Some(handle);
    }

    pub fn abort(&self) {
        if let Some(handle) = self.abort_handle.lock().take() {
            handle.abort();
        }
    }

    fn is_expired(&self, ttl: Duration) -> bool {
        match self.location {
            TaskLocation::Local => self
                .finished_at
                .lock()
                .is_some_and(|finished| finished.elapsed() > ttl),
            TaskLocation::Remote { .. } => self.created_at.elapsed() > ttl,
        }
    }
}

/// Concurrent store of all tasks known by this node.
#[derive(Default)]
pub struct TaskStore {
    records: DashMap<String, Arc<TaskRecord>, ahash::RandomState>,
}

impl TaskStore {
    pub fn get(&self, task_id: &str) -> Option<Arc<TaskRecord>> {
        self.records
            .get(task_id)
            .map(|entry| Arc::clone(entry.value()))
    }

    /// Inserts the record unless the id is taken. Returns the stored record and whether it was inserted.
    pub fn insert_if_absent(&self, record: Arc<TaskRecord>) -> (Arc<TaskRecord>, bool) {
        match self.records.entry(record.task_id.clone()) {
            Entry::Occupied(entry) => (Arc::clone(entry.get()), false),
            Entry::Vacant(entry) => {
                entry.insert(Arc::clone(&record));
                (record, true)
            }
        }
    }

    /// Removes exactly this record, leaving any newer record with the same id untouched.
    pub fn remove(&self, record: &Arc<TaskRecord>) {
        self.records
            .remove_if(&record.task_id, |_, stored| Arc::ptr_eq(stored, record));
    }

    /// Counts local tasks as `(pending, running)`.
    pub fn local_load(&self) -> (u32, u32) {
        self.records.iter().filter(|entry| entry.is_local()).fold(
            (0, 0),
            |(pending, running), entry| match entry.snapshot().status {
                TaskStatus::Pending => (pending + 1, running),
                TaskStatus::Running => (pending, running + 1),
                _ => (pending, running),
            },
        )
    }

    /// Removes finished local records and forwarded records older than `ttl`.
    pub fn evict_expired(&self, ttl: Duration) -> usize {
        let before = self.records.len();
        self.records.retain(|_, record| !record.is_expired(ttl));
        before.saturating_sub(self.records.len())
    }

    pub fn len(&self) -> usize {
        self.records.len()
    }

    pub fn is_empty(&self) -> bool {
        self.records.is_empty()
    }
}
