use k8s_openapi::api::{
    batch::v1::{Job, JobSpec},
    core::v1::{
        ConfigMapVolumeSource, Container, EnvVar, EnvVarSource, ObjectFieldSelector,
        PersistentVolumeClaimVolumeSource, PodSpec, PodTemplateSpec, ResourceRequirements, Volume,
        VolumeMount,
    },
};
use k8s_openapi::apimachinery::pkg::api::resource::Quantity;
use kube::{
    api::{Api, DeleteParams, PostParams},
    Client, ResourceExt,
};
use std::collections::BTreeMap;
use task_common::{
    crd::{Task, TaskCrdSpec, TaskResourceRequirements},
    error::{TaskError, TaskResult},
};
use tracing::info;

/// Kubernetes job manager for task execution
pub struct JobManager {
    /// Kubernetes client
    client: Client,

    /// Namespace
    namespace: String,

    /// Worker image
    worker_image: String,

    /// Service account name
    service_account: String,
}

impl JobManager {
    /// Create a new job manager
    pub async fn new(
        namespace: String,
        worker_image: String,
        service_account: String,
    ) -> TaskResult<Self> {
        let client = Client::try_default()
            .await
            .map_err(|e| TaskError::KubernetesError(format!("Failed to create client: {}", e)))?;

        Ok(Self {
            client,
            namespace,
            worker_image,
            service_account,
        })
    }

    /// Create a Kubernetes Job for a task
    pub async fn create_job(&self, task: &Task) -> TaskResult<String> {
        let task_name = task.name_any();
        let job_name = format!("task-{}", task_name);

        info!(
            "Creating Kubernetes Job {} for task {}",
            job_name, task_name
        );

        // Build container
        let container = self.build_container(&task.spec)?;

        // Build pod spec
        let pod_spec = PodSpec {
            service_account_name: Some(self.service_account.clone()),
            restart_policy: Some("Never".to_string()),
            containers: vec![container],
            volumes: self.build_volumes(&task.spec),
            node_selector: if task.spec.node_selector.is_empty() {
                None
            } else {
                Some(task.spec.node_selector.clone().into_iter().collect())
            },
            ..Default::default()
        };

        // Build job spec
        let job_spec = JobSpec {
            parallelism: Some(1),
            completions: Some(1),
            backoff_limit: Some(task.spec.retry_policy.max_retries as i32),
            active_deadline_seconds: Some(task.spec.timeout_seconds as i64),
            ttl_seconds_after_finished: Some(300), // Clean up after 5 minutes
            template: PodTemplateSpec {
                metadata: Some(k8s_openapi::apimachinery::pkg::apis::meta::v1::ObjectMeta {
                    labels: Some(self.build_labels(&task_name)),
                    annotations: Some(self.build_annotations(task)),
                    ..Default::default()
                }),
                spec: Some(pod_spec),
            },
            ..Default::default()
        };

        // Create job
        let job = Job {
            metadata: k8s_openapi::apimachinery::pkg::apis::meta::v1::ObjectMeta {
                name: Some(job_name.clone()),
                namespace: Some(self.namespace.clone()),
                labels: Some(self.build_labels(&task_name)),
                ..Default::default()
            },
            spec: Some(job_spec),
            ..Default::default()
        };

        let api: Api<Job> = Api::namespaced(self.client.clone(), &self.namespace);
        let created_job = api
            .create(&PostParams::default(), &job)
            .await
            .map_err(|e| TaskError::KubernetesError(format!("Failed to create job: {}", e)))?;

        let job_uid = created_job.uid().unwrap_or_default();
        info!("Created Kubernetes Job {} with UID {}", job_name, job_uid);

        Ok(job_uid)
    }

    /// Delete a Kubernetes Job
    pub async fn delete_job(&self, job_name: &str) -> TaskResult<()> {
        info!("Deleting Kubernetes Job {}", job_name);

        let api: Api<Job> = Api::namespaced(self.client.clone(), &self.namespace);

        let dp = DeleteParams::background();

        api.delete(job_name, &dp)
            .await
            .map_err(|e| TaskError::KubernetesError(format!("Failed to delete job: {}", e)))?;

        Ok(())
    }

    /// Get job status
    pub async fn get_job_status(&self, job_name: &str) -> TaskResult<Option<JobStatus>> {
        let api: Api<Job> = Api::namespaced(self.client.clone(), &self.namespace);

        match api.get(job_name).await {
            Ok(job) => {
                if let Some(status) = job.status {
                    Ok(Some(JobStatus {
                        active: status.active.unwrap_or(0) as u32,
                        succeeded: status.succeeded.unwrap_or(0) as u32,
                        failed: status.failed.unwrap_or(0) as u32,
                        conditions: status
                            .conditions
                            .unwrap_or_default()
                            .into_iter()
                            .map(|c| (c.type_, c.status))
                            .collect(),
                    }))
                } else {
                    Ok(None)
                }
            }
            Err(kube::Error::Api(e)) if e.code == 404 => Ok(None),
            Err(e) => Err(TaskError::KubernetesError(format!(
                "Failed to get job status: {}",
                e
            ))),
        }
    }

    /// Build container for the task
    fn build_container(&self, spec: &TaskCrdSpec) -> TaskResult<Container> {
        let mut env_vars = vec![
            EnvVar {
                name: "TASK_METHOD".to_string(),
                value: Some(spec.method.clone()),
                ..Default::default()
            },
            EnvVar {
                name: "TASK_ARGS".to_string(),
                value: Some(
                    serde_json::to_string(&spec.args)
                        .map_err(|e| TaskError::SerializationError(e.to_string()))?,
                ),
                ..Default::default()
            },
            EnvVar {
                name: "TASK_TIMEOUT".to_string(),
                value: Some(spec.timeout_seconds.to_string()),
                ..Default::default()
            },
            // Pod metadata
            EnvVar {
                name: "POD_NAME".to_string(),
                value_from: Some(EnvVarSource {
                    field_ref: Some(ObjectFieldSelector {
                        field_path: "metadata.name".to_string(),
                        ..Default::default()
                    }),
                    ..Default::default()
                }),
                ..Default::default()
            },
            EnvVar {
                name: "POD_NAMESPACE".to_string(),
                value_from: Some(EnvVarSource {
                    field_ref: Some(ObjectFieldSelector {
                        field_path: "metadata.namespace".to_string(),
                        ..Default::default()
                    }),
                    ..Default::default()
                }),
                ..Default::default()
            },
        ];

        // Add plugin environment variables
        if let Some(plugin) = &spec.plugin {
            env_vars.push(EnvVar {
                name: "PLUGIN_NAME".to_string(),
                value: Some(plugin.name.clone()),
                ..Default::default()
            });

            env_vars.push(EnvVar {
                name: "PLUGIN_VERSION".to_string(),
                value: Some(plugin.version.clone()),
                ..Default::default()
            });

            // Add custom config as env vars
            for (key, value) in &plugin.config {
                env_vars.push(EnvVar {
                    name: format!("PLUGIN_CONFIG_{}", key.to_uppercase()),
                    value: Some(value.clone()),
                    ..Default::default()
                });
            }
        }

        // Build volume mounts
        let mut volume_mounts = Vec::new();

        if let Some(plugin) = &spec.plugin {
            if plugin.config_map.is_some() {
                volume_mounts.push(VolumeMount {
                    name: "plugin-configmap".to_string(),
                    mount_path: "/plugins/configmap".to_string(),
                    read_only: Some(true),
                    ..Default::default()
                });
            }

            if plugin.pvc.is_some() {
                volume_mounts.push(VolumeMount {
                    name: "plugin-pvc".to_string(),
                    mount_path: "/plugins/pvc".to_string(),
                    read_only: Some(true),
                    ..Default::default()
                });
            }
        }

        Ok(Container {
            name: "task-executor".to_string(),
            image: Some(self.worker_image.clone()),
            image_pull_policy: Some("IfNotPresent".to_string()),
            env: Some(env_vars),
            volume_mounts: if volume_mounts.is_empty() {
                None
            } else {
                Some(volume_mounts)
            },
            resources: self.build_resource_requirements(&spec.resources),
            args: Some(vec!["--mode".to_string(), "job".to_string()]),
            ..Default::default()
        })
    }

    /// Build resource requirements
    fn build_resource_requirements(
        &self,
        resources: &TaskResourceRequirements,
    ) -> Option<ResourceRequirements> {
        let mut requests = BTreeMap::new();
        let mut limits = BTreeMap::new();

        if let Some(cpu) = &resources.cpu_request {
            requests.insert("cpu".to_string(), Quantity(cpu.clone()));
        }

        if let Some(cpu) = &resources.cpu_limit {
            limits.insert("cpu".to_string(), Quantity(cpu.clone()));
        }

        if let Some(memory) = &resources.memory_request {
            requests.insert("memory".to_string(), Quantity(memory.clone()));
        }

        if let Some(memory) = &resources.memory_limit {
            limits.insert("memory".to_string(), Quantity(memory.clone()));
        }

        if let Some(storage) = &resources.ephemeral_storage_request {
            requests.insert("ephemeral-storage".to_string(), Quantity(storage.clone()));
        }

        if let Some(storage) = &resources.ephemeral_storage_limit {
            limits.insert("ephemeral-storage".to_string(), Quantity(storage.clone()));
        }

        if requests.is_empty() && limits.is_empty() {
            None
        } else {
            Some(ResourceRequirements {
                requests: if requests.is_empty() {
                    None
                } else {
                    Some(requests)
                },
                limits: if limits.is_empty() {
                    None
                } else {
                    Some(limits)
                },
                ..Default::default()
            })
        }
    }

    /// Build volumes for the pod
    fn build_volumes(&self, spec: &TaskCrdSpec) -> Option<Vec<Volume>> {
        let mut volumes = Vec::new();

        if let Some(plugin) = &spec.plugin {
            if let Some(cm) = &plugin.config_map {
                volumes.push(Volume {
                    name: "plugin-configmap".to_string(),
                    config_map: Some(ConfigMapVolumeSource {
                        name: cm.clone(),
                        ..Default::default()
                    }),
                    ..Default::default()
                });
            }

            if let Some(pvc) = &plugin.pvc {
                volumes.push(Volume {
                    name: "plugin-pvc".to_string(),
                    persistent_volume_claim: Some(PersistentVolumeClaimVolumeSource {
                        claim_name: pvc.clone(),
                        read_only: Some(true),
                    }),
                    ..Default::default()
                });
            }
        }

        if volumes.is_empty() {
            None
        } else {
            Some(volumes)
        }
    }

    /// Build labels for the job
    fn build_labels(&self, task_name: &str) -> BTreeMap<String, String> {
        let mut labels = BTreeMap::new();
        labels.insert("app".to_string(), "task-scheduler".to_string());
        labels.insert("component".to_string(), "task-job".to_string());
        labels.insert("task".to_string(), task_name.to_string());
        labels
    }

    /// Build annotations for the pod
    fn build_annotations(&self, task: &Task) -> BTreeMap<String, String> {
        let mut annotations = BTreeMap::new();
        annotations.insert("task.scheduler/name".to_string(), task.name_any());
        annotations.insert(
            "task.scheduler/method".to_string(),
            task.spec.method.clone(),
        );
        annotations.insert(
            "task.scheduler/priority".to_string(),
            task.spec.priority.to_string(),
        );
        annotations
    }
}

/// Job status summary
#[derive(Debug, Clone)]
pub struct JobStatus {
    pub active: u32,
    pub succeeded: u32,
    pub failed: u32,
    pub conditions: Vec<(String, String)>,
}
