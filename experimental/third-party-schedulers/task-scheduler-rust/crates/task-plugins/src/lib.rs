pub mod k8s;
pub mod loader;
/// Task plugins module
pub mod plugin;
pub mod registry;
pub mod wasm;

pub use loader::PluginLoader;
pub use plugin::{Plugin, PluginConfig, PluginError, PluginInfo, PluginResult};
pub use registry::PluginRegistry;
