use crate::plugins::PluginManager;
use notify::{Watcher, RecommendedWatcher, RecursiveMode, Event, EventKind};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, Instant};
use std::collections::HashMap;
use task_common::error::{TaskError, TaskResult};
use tokio::sync::{RwLock, mpsc};
use tracing::{info, warn, error, debug};

/// Plugin hot reload manager
pub struct HotReloadManager {
    /// Plugin manager reference
    plugin_manager: Arc<PluginManager>,
    
    /// Watched directories
    watched_dirs: Arc<RwLock<Vec<PathBuf>>>,
    
    /// Plugin file metadata for change detection
    plugin_metadata: Arc<RwLock<HashMap<PathBuf, PluginMetadata>>>,
    
    /// Reload cooldown to prevent rapid reloads
    reload_cooldown: Duration,
    
    /// Channel for file system events
    event_tx: mpsc::Sender<ReloadEvent>,
    event_rx: Arc<RwLock<mpsc::Receiver<ReloadEvent>>>,
}

#[derive(Clone, Debug)]
struct PluginMetadata {
    path: PathBuf,
    #[allow(dead_code)]
    last_modified: std::time::SystemTime,
    size: u64,
    #[allow(dead_code)]
    checksum: Option<String>,
    plugin_name: Option<String>,
    last_reload: Instant,
}

#[derive(Debug)]
enum ReloadEvent {
    FileChanged(PathBuf),
    FileCreated(PathBuf),
    FileRemoved(PathBuf),
}

impl HotReloadManager {
    /// Create a new hot reload manager
    pub fn new(plugin_manager: Arc<PluginManager>) -> Self {
        let (tx, rx) = mpsc::channel(100);
        
        Self {
            plugin_manager,
            watched_dirs: Arc::new(RwLock::new(Vec::new())),
            plugin_metadata: Arc::new(RwLock::new(HashMap::new())),
            reload_cooldown: Duration::from_secs(2),
            event_tx: tx,
            event_rx: Arc::new(RwLock::new(rx)),
        }
    }
    
    /// Start watching directories for plugin changes
    pub async fn start_watching(&self, plugin_dirs: Vec<PathBuf>) -> TaskResult<()> {
        info!("Starting hot reload manager for {} directories", plugin_dirs.len());
        
        // Store watched directories
        {
            let mut dirs = self.watched_dirs.write().await;
            *dirs = plugin_dirs.clone();
        }
        
        // Scan initial plugins
        for dir in &plugin_dirs {
            self.scan_directory(dir).await?;
        }
        
        // Start file watcher
        self.start_file_watcher(plugin_dirs)?;
        
        // Start event processor
        self.start_event_processor();
        
        Ok(())
    }
    
    /// Scan directory for plugins
    async fn scan_directory(&self, dir: &Path) -> TaskResult<()> {
        if !dir.exists() {
            warn!("Plugin directory does not exist: {}", dir.display());
            return Ok(());
        }
        
        let mut metadata = self.plugin_metadata.write().await;
        
        for entry in std::fs::read_dir(dir)
            .map_err(|e| TaskError::PluginError(format!("Failed to read directory: {}", e)))?
        {
            let entry = entry.map_err(|e| TaskError::PluginError(e.to_string()))?;
            let path = entry.path();
            
            if self.is_plugin_file(&path) {
                if let Ok(file_metadata) = entry.metadata() {
                    let plugin_meta = PluginMetadata {
                        path: path.clone(),
                        last_modified: file_metadata.modified().unwrap_or(std::time::SystemTime::now()),
                        size: file_metadata.len(),
                        checksum: None, // TODO: Calculate checksum
                        plugin_name: None,
                        last_reload: Instant::now(),
                    };
                    
                    metadata.insert(path.clone(), plugin_meta);
                    
                    // Load plugin initially
                    if let Err(e) = self.load_plugin(&path).await {
                        error!("Failed to load plugin {}: {}", path.display(), e);
                    }
                }
            }
        }
        
        Ok(())
    }
    
    /// Check if file is a plugin
    fn is_plugin_file(&self, path: &Path) -> bool {
        if let Some(ext) = path.extension() {
            match ext.to_str() {
                Some("so") | Some("dll") | Some("dylib") => true,
                _ => false,
            }
        } else {
            false
        }
    }
    
    /// Start file system watcher
    fn start_file_watcher(&self, dirs: Vec<PathBuf>) -> TaskResult<()> {
        let tx = self.event_tx.clone();
        
        std::thread::spawn(move || {
            let (watcher_tx, watcher_rx) = std::sync::mpsc::channel();
            
            let mut watcher = RecommendedWatcher::new(
                watcher_tx,
                notify::Config::default().with_poll_interval(Duration::from_secs(1)),
            ).expect("Failed to create file watcher");
            
            // Watch all directories
            for dir in dirs {
                if let Err(e) = watcher.watch(&dir, RecursiveMode::NonRecursive) {
                    error!("Failed to watch directory {}: {}", dir.display(), e);
                }
            }
            
            // Process file system events
            for res in watcher_rx {
                match res {
                    Ok(event) => {
                        if let Err(e) = Self::handle_fs_event(event, &tx) {
                            error!("Failed to handle FS event: {}", e);
                        }
                    }
                    Err(e) => error!("Watch error: {}", e),
                }
            }
        });
        
        Ok(())
    }
    
    /// Handle file system event
    fn handle_fs_event(event: Event, tx: &mpsc::Sender<ReloadEvent>) -> TaskResult<()> {
        match event.kind {
            EventKind::Create(_) => {
                for path in event.paths {
                    let _ = tx.blocking_send(ReloadEvent::FileCreated(path));
                }
            }
            EventKind::Modify(_) => {
                for path in event.paths {
                    let _ = tx.blocking_send(ReloadEvent::FileChanged(path));
                }
            }
            EventKind::Remove(_) => {
                for path in event.paths {
                    let _ = tx.blocking_send(ReloadEvent::FileRemoved(path));
                }
            }
            _ => {}
        }
        
        Ok(())
    }
    
    /// Start event processor
    fn start_event_processor(&self) {
        let plugin_manager = self.plugin_manager.clone();
        let metadata = self.plugin_metadata.clone();
        let event_rx = self.event_rx.clone();
        let cooldown = self.reload_cooldown;
        
        tokio::spawn(async move {
            let mut rx = event_rx.write().await;
            
            while let Some(event) = rx.recv().await {
                match event {
                    ReloadEvent::FileChanged(path) | ReloadEvent::FileCreated(path) => {
                        // Check cooldown
                        let should_reload = {
                            let meta = metadata.read().await;
                            if let Some(plugin_meta) = meta.get(&path) {
                                plugin_meta.last_reload.elapsed() > cooldown
                            } else {
                                true
                            }
                        };
                        
                        if should_reload {
                            info!("Reloading plugin: {}", path.display());
                            
                            // Unload existing plugin if loaded
                            if let Some(plugin_name) = Self::get_plugin_name(&metadata, &path).await {
                                if let Err(e) = plugin_manager.unload_plugin(&plugin_name).await {
                                    warn!("Failed to unload plugin {}: {}", plugin_name, e);
                                }
                            }
                            
                            // Load new version
                            match plugin_manager.load_plugin(&path.to_string_lossy()).await {
                                Ok(tasks) => {
                                    info!("Reloaded plugin {} with {} tasks", path.display(), tasks.len());
                                    
                                    // Update metadata
                                    let mut meta = metadata.write().await;
                                    if let Some(plugin_meta) = meta.get_mut(&path) {
                                        plugin_meta.last_reload = Instant::now();
                                        plugin_meta.plugin_name = tasks.first()
                                            .and_then(|t| t.split("::").next())
                                            .map(String::from);
                                    }
                                }
                                Err(e) => {
                                    error!("Failed to reload plugin {}: {}", path.display(), e);
                                }
                            }
                        } else {
                            debug!("Skipping reload of {} due to cooldown", path.display());
                        }
                    }
                    ReloadEvent::FileRemoved(path) => {
                        info!("Plugin removed: {}", path.display());
                        
                        // Unload plugin
                        if let Some(plugin_name) = Self::get_plugin_name(&metadata, &path).await {
                            if let Err(e) = plugin_manager.unload_plugin(&plugin_name).await {
                                warn!("Failed to unload removed plugin {}: {}", plugin_name, e);
                            }
                        }
                        
                        // Remove metadata
                        metadata.write().await.remove(&path);
                    }
                }
            }
        });
    }
    
    /// Get plugin name from metadata
    async fn get_plugin_name(
        metadata: &Arc<RwLock<HashMap<PathBuf, PluginMetadata>>>,
        path: &Path,
    ) -> Option<String> {
        metadata.read().await
            .get(path)
            .and_then(|m| m.plugin_name.clone())
    }
    
    /// Load a plugin
    async fn load_plugin(&self, path: &Path) -> TaskResult<()> {
        self.plugin_manager.load_plugin(&path.to_string_lossy()).await?;
        Ok(())
    }
    
    /// Get hot reload status
    pub async fn get_status(&self) -> HotReloadStatus {
        let dirs = self.watched_dirs.read().await;
        let metadata = self.plugin_metadata.read().await;
        
        let plugins: Vec<_> = metadata.values()
            .map(|m| PluginStatus {
                path: m.path.display().to_string(),
                loaded: m.plugin_name.is_some(),
                last_reload: m.last_reload,
                size: m.size,
            })
            .collect();
        
        HotReloadStatus {
            enabled: true,
            watched_directories: dirs.iter().map(|d| d.display().to_string()).collect(),
            loaded_plugins: plugins.len(),
            plugins,
        }
    }
}

#[derive(Debug, Clone)]
pub struct HotReloadStatus {
    pub enabled: bool,
    pub watched_directories: Vec<String>,
    pub loaded_plugins: usize,
    pub plugins: Vec<PluginStatus>,
}

#[derive(Debug, Clone)]
pub struct PluginStatus {
    pub path: String,
    pub loaded: bool,
    pub last_reload: Instant,
    pub size: u64,
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;
    
    #[tokio::test]
    async fn test_hot_reload_manager() {
        let plugin_manager = Arc::new(PluginManager::new());
        let hot_reload = HotReloadManager::new(plugin_manager);
        
        // Create temp directory
        let temp_dir = TempDir::new().unwrap();
        let plugin_dir = temp_dir.path().to_path_buf();
        
        // Start watching
        hot_reload.start_watching(vec![plugin_dir.clone()]).await.unwrap();
        
        // Get status
        let status = hot_reload.get_status().await;
        assert!(status.enabled);
        assert_eq!(status.watched_directories.len(), 1);
    }
}