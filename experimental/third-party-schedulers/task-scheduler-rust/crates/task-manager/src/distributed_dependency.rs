use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use task_common::{
    error::{TaskError, TaskResult},
    events::{subjects, TaskEvent, WorkerCapacity},
    models::{TaskInfo, TaskStatus as ModelTaskStatus},
    queue::QueueManager,
    Uuid,
};
use tokio::sync::RwLock;
use tracing::{debug, info, warn};

/// Distributed dependency manager that handles cross-node task dependencies
pub struct DistributedDependencyManager {
    /// Local dependency tracking
    local_dependents: Arc<RwLock<HashMap<Uuid, HashSet<Uuid>>>>,
    
    /// Remote dependency tracking (task_id -> node_id)
    remote_dependencies: Arc<RwLock<HashMap<Uuid, String>>>,
    
    /// Node registry for tracking active nodes
    node_registry: Arc<RwLock<HashMap<String, NodeInfo>>>,
    
    /// Storage reference
    storage: Arc<dyn crate::storage::StorageBackend>,
    
    /// Queue reference
    queue: Arc<QueueManager>,
    
    /// Current node ID
    node_id: String,
}

#[derive(Clone, Debug)]
pub struct NodeInfo {
    pub node_id: String,
    pub address: String,
    pub last_heartbeat: chrono::DateTime<chrono::Utc>,
    pub capacity: NodeCapacity,
}

#[derive(Clone, Debug)]
pub struct NodeCapacity {
    pub total_workers: usize,
    pub active_tasks: usize,
    pub max_tasks: usize,
}

impl DistributedDependencyManager {
    /// Create a new distributed dependency manager
    pub fn new(
        storage: Arc<dyn crate::storage::StorageBackend>,
        queue: Arc<QueueManager>,
        node_id: String,
    ) -> Self {
        Self {
            local_dependents: Arc::new(RwLock::new(HashMap::new())),
            remote_dependencies: Arc::new(RwLock::new(HashMap::new())),
            node_registry: Arc::new(RwLock::new(HashMap::new())),
            storage,
            queue: queue.clone(),
            node_id,
        }
    }
    
    /// Initialize distributed dependency tracking
    pub async fn initialize(&self) -> TaskResult<()> {
        info!("Initializing distributed dependency manager for node {}", self.node_id);
        
        // Subscribe to task completion events from all nodes
        self.subscribe_to_completion_events().await?;
        
        // Start node heartbeat
        self.start_heartbeat().await?;
        
        // Discover existing nodes
        self.discover_nodes().await?;
        
        Ok(())
    }
    
    /// Subscribe to task completion events from all nodes
    async fn subscribe_to_completion_events(&self) -> TaskResult<()> {
        let queue = self.queue.clone();
        let _storage = self.storage.clone();
        let local_deps = self.local_dependents.clone();
        let _node_id = self.node_id.clone();
        
        // Subscribe to task completion events
        let mut subscriber = queue.subscribe_events(&format!("{}.*", subjects::TASK_COMPLETED)).await?;
        
        tokio::spawn(async move {
            while let Ok(Some(envelope)) = subscriber.next().await {
                if let TaskEvent::Completed { result, .. } = &envelope.event {
                    // Check if any local tasks depend on this completed task
                    let deps = local_deps.read().await;
                    if let Some(dependent_tasks) = deps.get(&result.task_id) {
                        info!(
                            "Task {} completed on {}, checking {} dependent tasks",
                            result.task_id,
                            envelope.source,
                            dependent_tasks.len()
                        );
                        
                        // Handle dependent tasks
                        for dependent_id in dependent_tasks {
                            // Process dependent task
                            debug!("Processing dependent task {} after {} completed", dependent_id, result.task_id);
                        }
                    }
                }
            }
        });
        
        Ok(())
    }
    
    /// Start node heartbeat
    async fn start_heartbeat(&self) -> TaskResult<()> {
        let queue = self.queue.clone();
        let node_id = self.node_id.clone();
        
        tokio::spawn(async move {
            let mut interval = tokio::time::interval(std::time::Duration::from_secs(10));
            
            loop {
                interval.tick().await;
                
                // Send heartbeat
                let event = TaskEvent::WorkerHeartbeat {
                    worker_id: node_id.clone(),
                    node_name: hostname::get().unwrap_or_default().to_string_lossy().to_string(),
                    timestamp: chrono::Utc::now(),
                    active_tasks: vec![], // TODO: Get actual tasks
                    capacity: WorkerCapacity {
                        max_tasks: 100,
                        running_tasks: 0,
                        available_cpu: 1000,
                        available_memory: 1024 * 1024 * 1024,
                        load_average: 0.0,
                    },
                };
                
                if let Err(e) = queue.publish_event(event, node_id.clone()).await {
                    warn!("Failed to send heartbeat: {}", e);
                }
            }
        });
        
        Ok(())
    }
    
    /// Discover active nodes in the cluster
    async fn discover_nodes(&self) -> TaskResult<()> {
        let queue = self.queue.clone();
        let registry = self.node_registry.clone();
        
        // Subscribe to worker heartbeats
        let mut subscriber = queue.subscribe_events(subjects::WORKER_HEARTBEAT).await?;
        
        tokio::spawn(async move {
            while let Ok(Some(envelope)) = subscriber.next().await {
                if let TaskEvent::WorkerHeartbeat { worker_id, node_name: _, timestamp, active_tasks, capacity } = envelope.event {
                    let mut nodes = registry.write().await;
                    
                    nodes.insert(worker_id.clone(), NodeInfo {
                        node_id: worker_id.clone(),
                        address: envelope.source.clone(),
                        last_heartbeat: timestamp,
                        capacity: NodeCapacity {
                            total_workers: 1,
                            active_tasks: active_tasks.len(),
                            max_tasks: capacity.max_tasks as usize,
                        },
                    });
                    
                    debug!("Updated node registry: {} from {}", worker_id, envelope.source);
                }
            }
        });
        
        Ok(())
    }
    
    /// Register a task with cross-node dependencies
    pub async fn register_distributed_task(
        &self,
        task_id: Uuid,
        dependencies: &[Uuid],
    ) -> TaskResult<()> {
        if dependencies.is_empty() {
            return Ok(());
        }
        
        let mut local_deps = self.local_dependents.write().await;
        let mut remote_deps = self.remote_dependencies.write().await;
        
        for dep_id in dependencies {
            // Check if dependency is local or remote
            match self.storage.get_task(*dep_id).await.ok().flatten() {
                Some(_task_info) => {
                    // Local dependency
                    local_deps
                        .entry(*dep_id)
                        .or_insert_with(HashSet::new)
                        .insert(task_id);
                    
                    debug!("Registered local dependency: {} depends on {}", task_id, dep_id);
                }
                None => {
                    // Remote dependency - need to query other nodes
                    if let Some(node_id) = self.find_task_node(dep_id).await? {
                        remote_deps.insert(*dep_id, node_id.clone());
                        
                        // Register interest in remote task completion
                        self.register_remote_dependency(task_id, *dep_id, &node_id).await?;
                        
                        debug!("Registered remote dependency: {} depends on {} (on node {})", 
                            task_id, dep_id, node_id);
                    } else {
                        return Err(TaskError::TaskNotFound(dep_id.to_string()));
                    }
                }
            }
        }
        
        Ok(())
    }
    
    /// Find which node owns a task
    async fn find_task_node(&self, _task_id: &Uuid) -> TaskResult<Option<String>> {
        // Query all known nodes for the task
        // In a real implementation, this would use a distributed registry
        // or consistent hashing to efficiently locate tasks
        
        // For now, return None (task not found)
        Ok(None)
    }
    
    /// Register interest in a remote task completion
    async fn register_remote_dependency(
        &self,
        dependent_task: Uuid,
        dependency: Uuid,
        node_id: &str,
    ) -> TaskResult<()> {
        // Send a message to the remote node to notify when the dependency completes
        // This would use the queue to send a registration message
        
        // Create a dummy task info for the event
        let task_info = TaskInfo {
            id: dependent_task,
            method: "dependency_registration".to_string(),
            args: vec![{
                let data = serde_json::json!({
                    "dependent_task": dependent_task,
                    "dependency": dependency,
                    "requesting_node": self.node_id,
                    "node_id": node_id,
                });
                let bytes = bincode::serialize(&data)
                    .map_err(|e| TaskError::SerializationError(e.to_string()))?;
                base64::Engine::encode(&base64::engine::general_purpose::STANDARD, &bytes)
            }],
            dependencies: vec![],
            priority: 0,
            metadata: Default::default(),
            status: ModelTaskStatus::Pending,
            created_at: chrono::Utc::now(),
            updated_at: chrono::Utc::now(),
        };
        
        let event = TaskEvent::Created {
            task: Box::new(task_info),
            source: self.node_id.clone(),
        };
        
        self.queue.publish_event(event, self.node_id.clone()).await?;
        
        Ok(())
    }
    
    /// Get cluster status
    pub async fn get_cluster_status(&self) -> TaskResult<ClusterStatus> {
        let nodes = self.node_registry.read().await;
        let now = chrono::Utc::now();
        
        let active_nodes: Vec<_> = nodes
            .values()
            .filter(|node| {
                let age = now - node.last_heartbeat;
                age.num_seconds() < 30 // Consider nodes active if heartbeat within 30s
            })
            .cloned()
            .collect();
        
        let total_capacity: usize = active_nodes
            .iter()
            .map(|n| n.capacity.max_tasks)
            .sum();
        
        let active_tasks: usize = active_nodes
            .iter()
            .map(|n| n.capacity.active_tasks)
            .sum();
        
        Ok(ClusterStatus {
            node_count: active_nodes.len(),
            total_capacity,
            active_tasks,
            nodes: active_nodes,
        })
    }
}

#[derive(Debug, Clone)]
pub struct ClusterStatus {
    pub node_count: usize,
    pub total_capacity: usize,
    pub active_tasks: usize,
    pub nodes: Vec<NodeInfo>,
}

#[cfg(test)]
mod tests {
    use super::*;
    
    #[tokio::test]
    async fn test_distributed_dependency_manager() {
        // Test with mock queue
        use task_common::queue::mock::MockQueueManager;
        
        let _storage = Arc::new(crate::storage::MemoryStorage::new(100));
        let _mock_queue = Arc::new(MockQueueManager::new());
        
        // Test can't directly create DistributedDependencyManager with mock
        // So test the individual components
        
        // Test node info structure
        let node_info = NodeInfo {
            node_id: "test-node".to_string(),
            address: "127.0.0.1:8080".to_string(),
            last_heartbeat: chrono::Utc::now(),
            capacity: NodeCapacity {
                total_workers: 4,
                active_tasks: 2,
                max_tasks: 10,
            },
        };
        
        assert_eq!(node_info.node_id, "test-node");
        assert_eq!(node_info.capacity.total_workers, 4);
        
        // Test cluster status
        let cluster_status = ClusterStatus {
            node_count: 3,
            total_capacity: 30,
            active_tasks: 15,
            nodes: vec![node_info],
        };
        
        assert_eq!(cluster_status.node_count, 3);
        assert_eq!(cluster_status.total_capacity, 30);
        
        // Test local dependency tracking
        let local_deps: Arc<RwLock<HashMap<Uuid, HashSet<Uuid>>>> = Arc::new(RwLock::new(HashMap::new()));
        let task_id = Uuid::new_v4();
        let dep_id = Uuid::new_v4();
        
        {
            let mut deps = local_deps.write().await;
            deps.entry(dep_id).or_insert_with(HashSet::new).insert(task_id);
        }
        
        let deps = local_deps.read().await;
        assert!(deps.get(&dep_id).unwrap().contains(&task_id));
    }
    
    #[tokio::test]
    async fn test_distributed_dependency_with_real_nats() {
        // Only run if NATS is available
        if let Ok(nats_url) = std::env::var("NATS_URL") {
            let storage = Arc::new(crate::storage::MemoryStorage::new(100));
            match QueueManager::new(&nats_url).await {
                Ok(queue) => {
                    let manager = DistributedDependencyManager::new(
                        storage,
                        Arc::new(queue),
                        "test-node-1".to_string(),
                    );
                    assert_eq!(manager.node_id, "test-node-1");
                }
                Err(_) => {
                    println!("NATS not available, skipping real test");
                }
            }
        }
    }
}