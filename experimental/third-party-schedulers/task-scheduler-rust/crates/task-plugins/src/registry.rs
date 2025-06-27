use crate::plugin::{Plugin, PluginConfig, PluginError, PluginEvent, PluginResult};
use dashmap::DashMap;
use std::sync::Arc;
use tokio::sync::RwLock;
use tracing::{debug, error, info};
use uuid::Uuid;

/// Plugin registry for managing loaded plugins
pub struct PluginRegistry {
    plugins: DashMap<String, Arc<RwLock<Box<dyn Plugin>>>>,
    configs: DashMap<String, PluginConfig>,
    event_handlers: Vec<Box<dyn Fn(PluginEvent) + Send + Sync>>,
}

impl PluginRegistry {
    /// Create a new plugin registry
    pub fn new() -> Self {
        Self {
            plugins: DashMap::new(),
            configs: DashMap::new(),
            event_handlers: Vec::new(),
        }
    }

    /// Register a plugin
    pub async fn register(
        &self,
        mut plugin: Box<dyn Plugin>,
        config: PluginConfig,
    ) -> PluginResult<()> {
        let info = plugin.info();
        let plugin_name = info.name.clone();

        info!("Registering plugin: {}", plugin_name);
        self.emit_event(PluginEvent::Loading {
            plugin_name: plugin_name.clone(),
        });

        // Initialize plugin
        self.emit_event(PluginEvent::Initializing {
            plugin_name: plugin_name.clone(),
        });
        plugin.init(config.clone()).await?;
        self.emit_event(PluginEvent::Initialized {
            plugin_name: plugin_name.clone(),
        });

        // Store plugin and config
        self.plugins
            .insert(plugin_name.clone(), Arc::new(RwLock::new(plugin)));
        self.configs.insert(plugin_name.clone(), config);

        self.emit_event(PluginEvent::Loaded {
            plugin_name: plugin_name.clone(),
        });
        info!("Successfully registered plugin: {}", plugin_name);

        Ok(())
    }

    /// Unregister a plugin
    pub async fn unregister(&self, name: &str) -> PluginResult<()> {
        info!("Unregistering plugin: {}", name);

        if let Some((_, plugin)) = self.plugins.remove(name) {
            self.emit_event(PluginEvent::Shutting {
                plugin_name: name.to_string(),
            });

            let mut plugin = plugin.write().await;
            plugin.shutdown().await?;

            self.configs.remove(name);
            self.emit_event(PluginEvent::Shutdown {
                plugin_name: name.to_string(),
            });

            info!("Successfully unregistered plugin: {}", name);
            Ok(())
        } else {
            Err(PluginError::NotFound(name.to_string()))
        }
    }

    /// Get a plugin by name
    pub fn get(&self, name: &str) -> Option<Arc<RwLock<Box<dyn Plugin>>>> {
        self.plugins.get(name).map(|p| p.clone())
    }

    /// Execute a plugin
    pub async fn execute(
        &self,
        name: &str,
        task_id: Uuid,
        input: serde_json::Value,
    ) -> PluginResult<serde_json::Value> {
        let plugin = self
            .get(name)
            .ok_or_else(|| PluginError::NotFound(name.to_string()))?;

        self.emit_event(PluginEvent::Executing {
            plugin_name: name.to_string(),
            task_id,
        });

        let plugin = plugin.read().await;
        let result = plugin.execute(input).await;

        let success = result.is_ok();
        self.emit_event(PluginEvent::Executed {
            plugin_name: name.to_string(),
            task_id,
            success,
        });

        if let Err(ref e) = result {
            error!("Plugin execution failed: {}", e);
            self.emit_event(PluginEvent::Error {
                plugin_name: name.to_string(),
                error: e.to_string(),
            });
        }

        result
    }

    /// List all registered plugins
    pub fn list_plugins(&self) -> Vec<String> {
        self.plugins.iter().map(|p| p.key().clone()).collect()
    }

    /// Get plugin config
    pub fn get_config(&self, name: &str) -> Option<PluginConfig> {
        self.configs.get(name).map(|c| c.clone())
    }

    /// Update plugin config
    pub async fn update_config(&self, name: &str, config: PluginConfig) -> PluginResult<()> {
        if self.plugins.contains_key(name) {
            self.configs.insert(name.to_string(), config);
            Ok(())
        } else {
            Err(PluginError::NotFound(name.to_string()))
        }
    }

    /// Health check all plugins
    pub async fn health_check_all(&self) -> Vec<(String, PluginResult<()>)> {
        let mut results = Vec::new();

        for entry in self.plugins.iter() {
            let name = entry.key().clone();
            let plugin = entry.value().clone();

            let plugin = plugin.read().await;
            let result = plugin.health_check().await;
            results.push((name, result));
        }

        results
    }

    /// Register an event handler
    pub fn on_event<F>(&mut self, handler: F)
    where
        F: Fn(PluginEvent) + Send + Sync + 'static,
    {
        self.event_handlers.push(Box::new(handler));
    }

    /// Emit an event to all handlers
    fn emit_event(&self, event: PluginEvent) {
        debug!("Plugin event: {:?}", event);
        for handler in &self.event_handlers {
            handler(event.clone());
        }
    }

    /// Shutdown all plugins
    pub async fn shutdown_all(&self) -> Vec<(String, PluginResult<()>)> {
        let mut results = Vec::new();

        for entry in self.plugins.iter() {
            let name = entry.key().clone();
            let plugin = entry.value().clone();

            self.emit_event(PluginEvent::Shutting {
                plugin_name: name.clone(),
            });

            let mut plugin = plugin.write().await;
            let result = plugin.shutdown().await;

            if result.is_ok() {
                self.emit_event(PluginEvent::Shutdown {
                    plugin_name: name.clone(),
                });
            } else {
                self.emit_event(PluginEvent::Error {
                    plugin_name: name.clone(),
                    error: format!("Shutdown failed: {:?}", result),
                });
            }

            results.push((name, result));
        }

        self.plugins.clear();
        self.configs.clear();

        results
    }
}

impl Default for PluginRegistry {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::plugin::PluginInfo;
    use async_trait::async_trait;
    use std::any::Any;

    struct TestPlugin {
        info: PluginInfo,
    }

    #[async_trait]
    impl Plugin for TestPlugin {
        fn info(&self) -> &PluginInfo {
            &self.info
        }

        async fn init(&mut self, _config: PluginConfig) -> PluginResult<()> {
            Ok(())
        }

        async fn execute(&self, input: serde_json::Value) -> PluginResult<serde_json::Value> {
            Ok(input)
        }

        async fn shutdown(&mut self) -> PluginResult<()> {
            Ok(())
        }

        fn as_any(&self) -> &dyn Any {
            self
        }
    }

    #[tokio::test]
    async fn test_plugin_registry() {
        let registry = PluginRegistry::new();

        let plugin = Box::new(TestPlugin {
            info: PluginInfo {
                id: Uuid::new_v4(),
                name: "test_plugin".to_string(),
                version: "1.0.0".to_string(),
                description: "Test plugin".to_string(),
                author: "Test".to_string(),
                dependencies: vec![],
                capabilities: vec![],
            },
        });

        let config = PluginConfig::default();

        // Register plugin
        registry.register(plugin, config).await.unwrap();

        // Check plugin exists
        assert!(registry.get("test_plugin").is_some());
        assert_eq!(registry.list_plugins(), vec!["test_plugin"]);

        // Execute plugin
        let result = registry
            .execute(
                "test_plugin",
                Uuid::new_v4(),
                serde_json::json!({"test": "data"}),
            )
            .await
            .unwrap();

        assert_eq!(result, serde_json::json!({"test": "data"}));

        // Unregister plugin
        registry.unregister("test_plugin").await.unwrap();
        assert!(registry.get("test_plugin").is_none());
    }
}
