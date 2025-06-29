// mod loader;
// mod plugin;
// mod sandbox;
mod simple;

// pub use loader::WasmPluginLoader;
// pub use plugin::{WasmPlugin, WasmPluginConfig};
// pub use sandbox::{WasmSandbox, SandboxConfig};
pub use simple::SimpleWasmPlugin;

// Re-export wasmtime types that might be useful
pub use wasmtime::{Engine, Instance, Module, Store};