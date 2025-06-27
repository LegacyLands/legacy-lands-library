use crate::plugin::{Plugin, PluginError, PluginResult};
use libloading::{Library, Symbol};
use std::path::Path;
use tracing::{debug, error, info};

/// Plugin loader for dynamic library loading
pub struct PluginLoader {
    loaded_libraries: Vec<Library>,
}

impl Default for PluginLoader {
    fn default() -> Self {
        Self::new()
    }
}

impl PluginLoader {
    /// Create a new plugin loader
    pub fn new() -> Self {
        Self {
            loaded_libraries: Vec::new(),
        }
    }

    /// Load a plugin from a shared library file
    pub fn load_from_file<P: AsRef<Path>>(&mut self, path: P) -> PluginResult<Box<dyn Plugin>> {
        let path = path.as_ref();
        info!("Loading plugin from: {}", path.display());

        unsafe {
            let lib = Library::new(path)
                .map_err(|e| PluginError::LoadError(format!("Failed to load library: {}", e)))?;

            let create_fn: Symbol<fn() -> *mut dyn Plugin> =
                lib.get(b"_plugin_create").map_err(|e| {
                    PluginError::LoadError(format!("Failed to find _plugin_create: {}", e))
                })?;

            let plugin_ptr = create_fn();
            if plugin_ptr.is_null() {
                return Err(PluginError::LoadError(
                    "Plugin creation returned null".to_string(),
                ));
            }

            let plugin = Box::from_raw(plugin_ptr);

            debug!("Successfully loaded plugin: {}", plugin.info().name);

            self.loaded_libraries.push(lib);
            Ok(plugin)
        }
    }

    /// Load all plugins from a directory
    pub fn load_from_directory<P: AsRef<Path>>(
        &mut self,
        dir: P,
    ) -> PluginResult<Vec<Box<dyn Plugin>>> {
        let dir = dir.as_ref();
        info!("Loading plugins from directory: {}", dir.display());

        if !dir.is_dir() {
            return Err(PluginError::LoadError(format!(
                "{} is not a directory",
                dir.display()
            )));
        }

        let mut plugins = Vec::new();

        let entries = std::fs::read_dir(dir)
            .map_err(|e| PluginError::LoadError(format!("Failed to read directory: {}", e)))?;

        for entry in entries {
            let entry = entry.map_err(|e| {
                PluginError::LoadError(format!("Failed to read directory entry: {}", e))
            })?;

            let path = entry.path();

            // Check if it's a shared library
            if let Some(ext) = path.extension() {
                let is_library = matches!(ext.to_str(), Some("so") | Some("dylib") | Some("dll"));

                if is_library {
                    match self.load_from_file(&path) {
                        Ok(plugin) => plugins.push(plugin),
                        Err(e) => error!("Failed to load plugin from {}: {}", path.display(), e),
                    }
                }
            }
        }

        info!("Loaded {} plugins from directory", plugins.len());
        Ok(plugins)
    }
}

impl Drop for PluginLoader {
    fn drop(&mut self) {
        // Libraries will be unloaded when dropped
        debug!("Unloading {} plugin libraries", self.loaded_libraries.len());
    }
}

/// Static plugin loader for built-in plugins
pub struct StaticPluginLoader {
    factories: Vec<Box<dyn Fn() -> Box<dyn Plugin> + Send + Sync>>,
}

impl Default for StaticPluginLoader {
    fn default() -> Self {
        Self::new()
    }
}

impl StaticPluginLoader {
    /// Create a new static plugin loader
    pub fn new() -> Self {
        Self {
            factories: Vec::new(),
        }
    }

    /// Register a plugin factory
    pub fn register<F>(&mut self, factory: F)
    where
        F: Fn() -> Box<dyn Plugin> + Send + Sync + 'static,
    {
        self.factories.push(Box::new(factory));
    }

    /// Load all registered plugins
    pub fn load_all(&self) -> Vec<Box<dyn Plugin>> {
        self.factories.iter().map(|f| f()).collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_plugin_loader_creation() {
        let loader = PluginLoader::new();
        assert_eq!(loader.loaded_libraries.len(), 0);
    }

    #[test]
    fn test_static_loader() {
        let loader = StaticPluginLoader::new();
        assert_eq!(loader.factories.len(), 0);

        // Would register actual plugins in real tests
    }
}
