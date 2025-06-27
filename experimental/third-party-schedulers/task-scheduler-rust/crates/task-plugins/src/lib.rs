pub mod k8s;
pub mod loader;
/// Task plugins module
pub mod plugin;
pub mod registry;

pub use loader::PluginLoader;
pub use plugin::{Plugin, PluginInfo, PluginResult};
pub use registry::PluginRegistry;
