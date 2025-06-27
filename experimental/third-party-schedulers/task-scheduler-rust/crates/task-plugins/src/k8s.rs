use crate::plugin::{PluginError, PluginResult};
use k8s_openapi::api::core::v1::ConfigMap;
use kube::{
    api::{Api, ListParams},
    Client,
};
use std::collections::HashMap;
use std::path::PathBuf;
use tracing::{debug, info};

/// Kubernetes plugin source
#[derive(Debug, Clone)]
pub enum PluginSource {
    /// Load from ConfigMap
    ConfigMap { name: String, namespace: String },
    /// Load from PersistentVolumeClaim
    PVC {
        name: String,
        namespace: String,
        path: String,
    },
    /// Load from container image
    Image { image: String, path: String },
}

/// Kubernetes plugin manager
pub struct K8sPluginManager {
    client: Client,
    plugin_dir: PathBuf,
}

impl K8sPluginManager {
    /// Create a new K8s plugin manager
    pub async fn new(plugin_dir: PathBuf) -> PluginResult<Self> {
        let client = Client::try_default()
            .await
            .map_err(|e| PluginError::ConfigError(format!("Failed to create K8s client: {}", e)))?;

        // Ensure plugin directory exists
        std::fs::create_dir_all(&plugin_dir)
            .map_err(|e| PluginError::ConfigError(format!("Failed to create plugin dir: {}", e)))?;

        Ok(Self { client, plugin_dir })
    }

    /// Load plugin from ConfigMap
    pub async fn load_from_configmap(
        &self,
        name: &str,
        namespace: &str,
    ) -> PluginResult<Vec<(String, Vec<u8>)>> {
        info!("Loading plugins from ConfigMap: {}/{}", namespace, name);

        let api: Api<ConfigMap> = Api::namespaced(self.client.clone(), namespace);

        let cm = api
            .get(name)
            .await
            .map_err(|e| PluginError::LoadError(format!("Failed to get ConfigMap: {}", e)))?;

        let mut plugins = Vec::new();

        if let Some(binary_data) = cm.binary_data {
            for (key, value) in binary_data {
                debug!("Found plugin in ConfigMap: {}", key);
                plugins.push((key, value.0));
            }
        }

        if let Some(data) = cm.data {
            for (key, value) in data {
                if key.ends_with(".so") || key.ends_with(".dylib") || key.ends_with(".dll") {
                    debug!("Found plugin in ConfigMap data: {}", key);
                    plugins.push((key, value.into_bytes()));
                }
            }
        }

        info!("Loaded {} plugins from ConfigMap", plugins.len());
        Ok(plugins)
    }

    /// Save plugins to local directory
    pub async fn save_plugins(
        &self,
        plugins: Vec<(String, Vec<u8>)>,
    ) -> PluginResult<Vec<PathBuf>> {
        let mut paths = Vec::new();

        for (name, data) in plugins {
            let path = self.plugin_dir.join(&name);

            std::fs::write(&path, data).map_err(|e| {
                PluginError::LoadError(format!("Failed to write plugin file: {}", e))
            })?;

            // Make executable on Unix
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                let mut perms = std::fs::metadata(&path)
                    .map_err(|e| PluginError::LoadError(format!("Failed to get metadata: {}", e)))?
                    .permissions();
                perms.set_mode(0o755);
                std::fs::set_permissions(&path, perms).map_err(|e| {
                    PluginError::LoadError(format!("Failed to set permissions: {}", e))
                })?;
            }

            debug!("Saved plugin to: {}", path.display());
            paths.push(path);
        }

        Ok(paths)
    }

    /// List available plugins in namespace
    pub async fn list_plugins(&self, namespace: &str) -> PluginResult<Vec<PluginSource>> {
        let mut sources = Vec::new();

        // List ConfigMaps with plugin label
        let api: Api<ConfigMap> = Api::namespaced(self.client.clone(), namespace);
        let lp = ListParams::default().labels("plugin-type=task-scheduler");

        let cms = api
            .list(&lp)
            .await
            .map_err(|e| PluginError::LoadError(format!("Failed to list ConfigMaps: {}", e)))?;

        for cm in cms {
            if let Some(name) = cm.metadata.name {
                sources.push(PluginSource::ConfigMap {
                    name,
                    namespace: namespace.to_string(),
                });
            }
        }

        info!(
            "Found {} plugin sources in namespace {}",
            sources.len(),
            namespace
        );
        Ok(sources)
    }

    /// Create plugin runtime container spec
    pub fn create_plugin_container_spec(
        &self,
        plugin_name: &str,
        plugin_version: &str,
    ) -> serde_json::Value {
        serde_json::json!({
            "name": format!("plugin-{}", plugin_name),
            "image": format!("task-scheduler/plugin-runtime:{}", plugin_version),
            "env": [
                {
                    "name": "PLUGIN_NAME",
                    "value": plugin_name
                },
                {
                    "name": "PLUGIN_VERSION",
                    "value": plugin_version
                }
            ],
            "volumeMounts": [
                {
                    "name": "plugin-volume",
                    "mountPath": "/plugins"
                }
            ],
            "resources": {
                "requests": {
                    "cpu": "100m",
                    "memory": "128Mi"
                },
                "limits": {
                    "cpu": "500m",
                    "memory": "512Mi"
                }
            }
        })
    }
}

/// Plugin version manager
pub struct PluginVersionManager {
    versions: HashMap<String, Vec<String>>,
}

impl Default for PluginVersionManager {
    fn default() -> Self {
        Self::new()
    }
}

impl PluginVersionManager {
    /// Create a new version manager
    pub fn new() -> Self {
        Self {
            versions: HashMap::new(),
        }
    }

    /// Register a plugin version
    pub fn register_version(&mut self, plugin_name: &str, version: &str) {
        self.versions
            .entry(plugin_name.to_string())
            .or_default()
            .push(version.to_string());
    }

    /// Get latest version
    pub fn get_latest(&self, plugin_name: &str) -> Option<&str> {
        self.versions
            .get(plugin_name)
            .and_then(|v| v.last())
            .map(|s| s.as_str())
    }

    /// Get all versions
    pub fn get_versions(&self, plugin_name: &str) -> Option<&[String]> {
        self.versions.get(plugin_name).map(|v| v.as_slice())
    }

    /// Check version compatibility
    pub fn is_compatible(&self, plugin_name: &str, required_version: &str) -> bool {
        if let Some(versions) = self.versions.get(plugin_name) {
            versions.contains(&required_version.to_string())
        } else {
            false
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_plugin_version_manager() {
        let mut manager = PluginVersionManager::new();

        manager.register_version("test-plugin", "1.0.0");
        manager.register_version("test-plugin", "1.1.0");
        manager.register_version("test-plugin", "2.0.0");

        assert_eq!(manager.get_latest("test-plugin"), Some("2.0.0"));
        assert!(manager.is_compatible("test-plugin", "1.1.0"));
        assert!(!manager.is_compatible("test-plugin", "3.0.0"));
    }
}
