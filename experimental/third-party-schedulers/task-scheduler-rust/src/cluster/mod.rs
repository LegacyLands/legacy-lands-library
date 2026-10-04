use crate::error::TaskError;
use crate::tasks::taskscheduler::task_response::Status as ProtoStatus;
use crate::tasks::taskscheduler::task_scheduler_client::TaskSchedulerClient;
use crate::tasks::taskscheduler::{
    CancelRequest, CancelResponse, NodeInfoRequest, PeerInfo, ResultRequest, ResultResponse,
    TaskRequest, TaskResponse, WaitResultRequest,
};
use crate::{info_log, warn_log};
use futures::stream::{FuturesUnordered, StreamExt};
use parking_lot::RwLock;
use std::collections::HashSet;
use std::sync::Arc;
use std::time::Duration;
use tonic::transport::{Certificate, Channel, ClientTlsConfig, Endpoint, Identity};
use tonic::Request;

/// Static configuration of the peer-to-peer cluster.
#[derive(Clone)]
pub struct ClusterConfig {
    /// Peer URIs such as `http://10.0.0.2:50051`; `https` URIs use TLS.
    pub peers: Vec<String>,
    /// CA certificate used to verify `https` peers, falls back to WebPKI roots when absent.
    pub peer_ca_cert: Option<Certificate>,
    /// Client identity presented to peers that require mTLS.
    pub peer_identity: Option<Identity>,
    pub heartbeat_interval: Duration,
    /// Timeout of short peer RPCs (heartbeat, lookup, cancel).
    pub request_timeout: Duration,
}

impl Default for ClusterConfig {
    fn default() -> Self {
        Self {
            peers: Vec::new(),
            peer_ca_cert: None,
            peer_identity: None,
            heartbeat_interval: Duration::from_secs(3),
            request_timeout: Duration::from_secs(5),
        }
    }
}

#[derive(Default)]
struct PeerState {
    node_id: Option<String>,
    alive: bool,
    methods: HashSet<String>,
    load: u32,
}

/// A remote scheduler node.
pub struct Peer {
    pub address: String,
    client: TaskSchedulerClient<Channel>,
    state: RwLock<PeerState>,
}

impl Peer {
    pub fn node_id(&self) -> Option<String> {
        self.state.read().node_id.clone()
    }

    pub fn is_alive(&self) -> bool {
        self.state.read().alive
    }

    /// Human readable label, preferring the node id over the address.
    pub fn label(&self) -> String {
        self.node_id().unwrap_or_else(|| self.address.clone())
    }

    fn client(&self) -> TaskSchedulerClient<Channel> {
        self.client.clone()
    }
}

/// Peer-to-peer view of the cluster from this node.
pub struct Cluster {
    peers: Vec<Arc<Peer>>,
    config: ClusterConfig,
}

impl Cluster {
    /// Creates the cluster with lazily connected peer channels, so peers may start later than this node.
    pub fn new(config: ClusterConfig) -> Result<Self, TaskError> {
        let peers = config
            .peers
            .iter()
            .map(|address| {
                let channel = Self::build_endpoint(address, &config)?.connect_lazy();
                Ok(Arc::new(Peer {
                    address: address.clone(),
                    client: TaskSchedulerClient::new(channel),
                    state: RwLock::new(PeerState::default()),
                }))
            })
            .collect::<Result<Vec<_>, TaskError>>()?;

        Ok(Self { peers, config })
    }

    fn build_endpoint(address: &str, config: &ClusterConfig) -> Result<Endpoint, TaskError> {
        let invalid = |e: &dyn std::fmt::Display| {
            TaskError::InvalidArguments(format!("Invalid peer address '{}': {}", address, e))
        };

        let mut endpoint = Endpoint::from_shared(address.to_string())
            .map_err(|e| invalid(&e))?
            .connect_timeout(config.request_timeout)
            .tcp_keepalive(Some(Duration::from_secs(30)));

        if address.starts_with("https://") {
            let mut tls = ClientTlsConfig::new().with_webpki_roots();
            if let Some(ca) = &config.peer_ca_cert {
                tls = tls.ca_certificate(ca.clone());
            }
            if let Some(identity) = &config.peer_identity {
                tls = tls.identity(identity.clone());
            }
            endpoint = endpoint.tls_config(tls).map_err(|e| invalid(&e))?;
        }

        Ok(endpoint)
    }

    pub fn is_empty(&self) -> bool {
        self.peers.is_empty()
    }

    pub fn request_timeout(&self) -> Duration {
        self.config.request_timeout
    }

    /// Starts the background heartbeat that keeps peer liveness, methods and load up to date.
    pub fn start_heartbeat(self: &Arc<Self>, own_node_id: &str) {
        if self.peers.is_empty() {
            return;
        }

        let cluster = Arc::clone(self);
        let own_node_id = own_node_id.to_string();
        tokio::spawn(async move {
            let mut interval = tokio::time::interval(cluster.config.heartbeat_interval);
            interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
            loop {
                interval.tick().await;
                futures::future::join_all(
                    cluster
                        .peers
                        .iter()
                        .map(|peer| cluster.refresh_peer(peer, &own_node_id)),
                )
                .await;
            }
        });
    }

    async fn refresh_peer(&self, peer: &Peer, own_node_id: &str) {
        let mut request = Request::new(NodeInfoRequest {});
        request.set_timeout(self.config.request_timeout);

        match peer.client().get_node_info(request).await {
            Ok(response) => {
                let info = response.into_inner();
                let mut state = peer.state.write();
                if !state.alive && info.node_id == own_node_id {
                    warn_log!(
                        "Peer '{}' reports the same node id '{}' as this node, node ids must be unique (see --node-id)",
                        peer.address,
                        info.node_id
                    );
                }
                if !state.alive {
                    info_log!(
                        "Peer '{}' ({}) is now reachable with {} methods",
                        info.node_id,
                        peer.address,
                        info.methods.len()
                    );
                }
                state.alive = true;
                state.node_id = Some(info.node_id);
                state.methods = info.methods.into_iter().collect();
                state.load = info.pending_tasks + info.running_tasks;
            }
            Err(status) => {
                let mut state = peer.state.write();
                if state.alive {
                    warn_log!(
                        "Peer '{}' became unreachable: {}",
                        peer.address,
                        status.message()
                    );
                }
                state.alive = false;
            }
        }
    }

    pub fn peer_infos(&self) -> Vec<PeerInfo> {
        self.peers
            .iter()
            .map(|peer| {
                let state = peer.state.read();
                PeerInfo {
                    node_id: state.node_id.clone().unwrap_or_default(),
                    address: peer.address.clone(),
                    alive: state.alive,
                }
            })
            .collect()
    }

    pub fn find_peer(&self, address: &str) -> Option<Arc<Peer>> {
        self.peers
            .iter()
            .find(|peer| peer.address == address)
            .cloned()
    }

    /// Picks the least loaded alive peer that provides `method`.
    pub fn select_peer_for(&self, method: &str) -> Option<Arc<Peer>> {
        self.peers
            .iter()
            .filter_map(|peer| {
                let state = peer.state.read();
                (state.alive && state.methods.contains(method)).then_some((state.load, peer))
            })
            .min_by_key(|(load, _)| *load)
            .map(|(_, peer)| Arc::clone(peer))
    }

    /// Asks all peers concurrently for `task_id` and returns the first peer that knows it.
    pub async fn locate(&self, task_id: &str) -> Option<(Arc<Peer>, ResultResponse)> {
        let mut lookups: FuturesUnordered<_> = self
            .peers
            .iter()
            .map(|peer| async move {
                let response = self.get_remote(peer, task_id).await.ok()?;
                (response.status != ProtoStatus::NotFound as i32)
                    .then(|| (Arc::clone(peer), response))
            })
            .collect();

        while let Some(found) = lookups.next().await {
            if found.is_some() {
                return found;
            }
        }
        None
    }

    pub async fn get_remote(
        &self,
        peer: &Peer,
        task_id: &str,
    ) -> Result<ResultResponse, tonic::Status> {
        let mut request = Request::new(ResultRequest {
            task_id: task_id.to_string(),
        });
        request.set_timeout(self.config.request_timeout);
        peer.client()
            .get_result(request)
            .await
            .map(|response| response.into_inner())
    }

    /// Long-polls the peer until the task is terminal or `timeout` elapses.
    pub async fn wait_remote(
        &self,
        peer: &Peer,
        task_id: &str,
        timeout: Duration,
    ) -> Result<ResultResponse, tonic::Status> {
        let mut request = Request::new(WaitResultRequest {
            task_id: task_id.to_string(),
            timeout_ms: timeout.as_millis() as u64,
        });
        request.set_timeout(timeout + self.config.request_timeout);
        peer.client()
            .wait_result(request)
            .await
            .map(|response| response.into_inner())
    }

    /// Submits a task to the peer. Blocking submissions carry no deadline because
    /// they may legitimately wait for dependencies and long executions.
    pub async fn submit_remote(
        &self,
        peer: &Peer,
        task: TaskRequest,
    ) -> Result<TaskResponse, tonic::Status> {
        let detached = task.detached;
        let mut request = Request::new(task);
        if detached {
            request.set_timeout(self.config.request_timeout);
        }
        peer.client()
            .submit_task(request)
            .await
            .map(|response| response.into_inner())
    }

    pub async fn cancel_remote(
        &self,
        peer: &Peer,
        task_id: &str,
    ) -> Result<CancelResponse, tonic::Status> {
        let mut request = Request::new(CancelRequest {
            task_id: task_id.to_string(),
        });
        request.set_timeout(self.config.request_timeout);
        peer.client()
            .cancel_task(request)
            .await
            .map(|response| response.into_inner())
    }
}

/// Converts a peer gRPC error into a task error that keeps the original status code.
pub fn remote_error(status: tonic::Status) -> TaskError {
    TaskError::Remote {
        code: status.code() as i32,
        message: status.message().to_string(),
    }
}
