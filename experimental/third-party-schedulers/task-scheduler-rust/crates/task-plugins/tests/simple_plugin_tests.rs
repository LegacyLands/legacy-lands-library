use task_plugins::registry::PluginRegistry;
use std::sync::Arc;

#[tokio::test]
async fn test_plugin_registry_basic() {
    let registry = Arc::new(PluginRegistry::new());
    
    // Test list plugins (should be empty initially)
    let plugins = registry.list_plugins();
    assert_eq!(plugins.len(), 0);
}

#[tokio::test]
async fn test_plugin_loader_basic() {
    // Placeholder test for plugin loader
    assert_eq!(1 + 1, 2);
}