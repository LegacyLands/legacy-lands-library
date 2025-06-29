use std::path::{Path, PathBuf};
use std::sync::Arc;
use thiserror::Error;
use tonic::transport::{Certificate, ClientTlsConfig, Identity, ServerTlsConfig};
use tracing::{debug, info};

/// TLS configuration errors
#[derive(Debug, Error)]
pub enum TlsError {
    #[error("Failed to read certificate file: {0}")]
    CertificateReadError(#[from] std::io::Error),

    #[error("Invalid certificate: {0}")]
    InvalidCertificate(String),

    #[error("Missing required TLS file: {0}")]
    MissingFile(String),

    #[error("TLS configuration error: {0}")]
    ConfigurationError(String),
}

pub type TlsResult<T> = Result<T, TlsError>;

/// TLS configuration for mTLS support
#[derive(Debug, Clone)]
pub struct TlsConfig {
    /// Server certificate (PEM format)
    pub server_cert: Option<Certificate>,

    /// Server private key with certificate (PEM format)
    pub server_identity: Option<Identity>,

    /// Client certificate for mutual TLS (PEM format)
    pub client_cert: Option<Certificate>,

    /// Client private key with certificate (PEM format)
    pub client_identity: Option<Identity>,

    /// Root CA certificate for verification (PEM format)
    pub ca_cert: Option<Certificate>,

    /// Whether to require client certificates
    pub require_client_cert: bool,

    /// Server name for SNI
    pub server_name: Option<String>,
}

impl TlsConfig {
    /// Create a new TLS configuration builder
    pub fn builder() -> TlsConfigBuilder {
        TlsConfigBuilder::new()
    }

    /// Create server TLS configuration for tonic
    pub fn server_config(&self) -> TlsResult<ServerTlsConfig> {
        let mut config = ServerTlsConfig::new();

        // Set server identity
        if let Some(identity) = &self.server_identity {
            config = config.identity(identity.clone());
        } else {
            return Err(TlsError::MissingFile("server identity".to_string()));
        }

        // Set client CA for mutual TLS
        if self.require_client_cert {
            if let Some(ca_cert) = &self.ca_cert {
                config = config.client_ca_root(ca_cert.clone());
            } else {
                return Err(TlsError::MissingFile("CA certificate".to_string()));
            }
        }

        Ok(config)
    }

    /// Create client TLS configuration for tonic
    pub fn client_config(&self) -> TlsResult<ClientTlsConfig> {
        let mut config = ClientTlsConfig::new();

        // Set server CA for verification
        if let Some(ca_cert) = &self.ca_cert {
            config = config.ca_certificate(ca_cert.clone());
        }

        // Set client identity for mutual TLS
        if let Some(identity) = &self.client_identity {
            config = config.identity(identity.clone());
        }

        // Set server name for SNI
        if let Some(server_name) = &self.server_name {
            config = config.domain_name(server_name);
        }

        Ok(config)
    }

    /// Validate the TLS configuration
    pub fn validate(&self) -> TlsResult<()> {
        // Check server configuration
        if self.server_identity.is_some() && self.server_cert.is_none() {
            return Err(TlsError::ConfigurationError(
                "Server identity requires server certificate".to_string(),
            ));
        }

        // Check client configuration
        if self.client_identity.is_some() && self.client_cert.is_none() {
            return Err(TlsError::ConfigurationError(
                "Client identity requires client certificate".to_string(),
            ));
        }

        // Check mutual TLS configuration
        if self.require_client_cert && self.ca_cert.is_none() {
            return Err(TlsError::ConfigurationError(
                "Mutual TLS requires CA certificate".to_string(),
            ));
        }

        Ok(())
    }
}

/// Builder for TLS configuration
pub struct TlsConfigBuilder {
    server_cert_path: Option<PathBuf>,
    server_key_path: Option<PathBuf>,
    client_cert_path: Option<PathBuf>,
    client_key_path: Option<PathBuf>,
    ca_cert_path: Option<PathBuf>,
    require_client_cert: bool,
    server_name: Option<String>,
}

impl TlsConfigBuilder {
    /// Create a new TLS configuration builder
    pub fn new() -> Self {
        Self {
            server_cert_path: None,
            server_key_path: None,
            client_cert_path: None,
            client_key_path: None,
            ca_cert_path: None,
            require_client_cert: false,
            server_name: None,
        }
    }

    /// Set server certificate path
    pub fn server_cert_path<P: AsRef<Path>>(mut self, path: P) -> Self {
        self.server_cert_path = Some(path.as_ref().to_path_buf());
        self
    }

    /// Set server private key path
    pub fn server_key_path<P: AsRef<Path>>(mut self, path: P) -> Self {
        self.server_key_path = Some(path.as_ref().to_path_buf());
        self
    }

    /// Set client certificate path
    pub fn client_cert_path<P: AsRef<Path>>(mut self, path: P) -> Self {
        self.client_cert_path = Some(path.as_ref().to_path_buf());
        self
    }

    /// Set client private key path
    pub fn client_key_path<P: AsRef<Path>>(mut self, path: P) -> Self {
        self.client_key_path = Some(path.as_ref().to_path_buf());
        self
    }

    /// Set CA certificate path
    pub fn ca_cert_path<P: AsRef<Path>>(mut self, path: P) -> Self {
        self.ca_cert_path = Some(path.as_ref().to_path_buf());
        self
    }

    /// Enable mutual TLS (require client certificates)
    pub fn require_client_cert(mut self, require: bool) -> Self {
        self.require_client_cert = require;
        self
    }

    /// Set server name for SNI
    pub fn server_name(mut self, name: impl Into<String>) -> Self {
        self.server_name = Some(name.into());
        self
    }

    /// Build the TLS configuration
    pub async fn build(self) -> TlsResult<TlsConfig> {
        let mut config = TlsConfig {
            server_cert: None,
            server_identity: None,
            client_cert: None,
            client_identity: None,
            ca_cert: None,
            require_client_cert: self.require_client_cert,
            server_name: self.server_name,
        };

        // Load server certificate and identity
        if let (Some(cert_path), Some(key_path)) = (&self.server_cert_path, &self.server_key_path) {
            debug!("Loading server certificate from {:?}", cert_path);
            let cert_pem = tokio::fs::read_to_string(cert_path).await?;
            let key_pem = tokio::fs::read_to_string(key_path).await?;

            config.server_cert = Some(Certificate::from_pem(&cert_pem));
            config.server_identity = Some(Identity::from_pem(&cert_pem, &key_pem));
            info!("Server TLS configured");
        }

        // Load client certificate and identity
        if let (Some(cert_path), Some(key_path)) = (&self.client_cert_path, &self.client_key_path) {
            debug!("Loading client certificate from {:?}", cert_path);
            let cert_pem = tokio::fs::read_to_string(cert_path).await?;
            let key_pem = tokio::fs::read_to_string(key_path).await?;

            config.client_cert = Some(Certificate::from_pem(&cert_pem));
            config.client_identity = Some(Identity::from_pem(&cert_pem, &key_pem));
            info!("Client TLS configured");
        }

        // Load CA certificate
        if let Some(ca_path) = &self.ca_cert_path {
            debug!("Loading CA certificate from {:?}", ca_path);
            let ca_pem = tokio::fs::read_to_string(ca_path).await?;
            config.ca_cert = Some(Certificate::from_pem(&ca_pem));
            info!("CA certificate loaded");
        }

        // Validate configuration
        config.validate()?;

        Ok(config)
    }
}

impl Default for TlsConfigBuilder {
    fn default() -> Self {
        Self::new()
    }
}

/// Helper function to create a secure server TLS configuration
pub async fn create_server_tls_config(
    cert_path: impl AsRef<Path>,
    key_path: impl AsRef<Path>,
    ca_path: Option<impl AsRef<Path>>,
) -> TlsResult<Arc<TlsConfig>> {
    let mut builder = TlsConfig::builder()
        .server_cert_path(cert_path)
        .server_key_path(key_path);

    if let Some(ca) = ca_path {
        builder = builder.ca_cert_path(ca).require_client_cert(true);
    }

    Ok(Arc::new(builder.build().await?))
}

/// Helper function to create a secure client TLS configuration
pub async fn create_client_tls_config(
    cert_path: Option<impl AsRef<Path>>,
    key_path: Option<impl AsRef<Path>>,
    ca_path: impl AsRef<Path>,
    server_name: impl Into<String>,
) -> TlsResult<Arc<TlsConfig>> {
    let mut builder = TlsConfig::builder()
        .ca_cert_path(ca_path)
        .server_name(server_name);

    if let (Some(cert), Some(key)) = (cert_path, key_path) {
        builder = builder.client_cert_path(cert).client_key_path(key);
    }

    Ok(Arc::new(builder.build().await?))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use tempfile::NamedTempFile;

    fn create_test_file(content: &str) -> NamedTempFile {
        let mut file = NamedTempFile::new().unwrap();
        file.write_all(content.as_bytes()).unwrap();
        file
    }

    #[tokio::test]
    async fn test_tls_config_builder() {
        let cert_file = create_test_file("CERT_CONTENT");
        let key_file = create_test_file("KEY_CONTENT");
        let ca_file = create_test_file("CA_CONTENT");

        let config = TlsConfig::builder()
            .server_cert_path(cert_file.path())
            .server_key_path(key_file.path())
            .ca_cert_path(ca_file.path())
            .require_client_cert(true)
            .server_name("test.example.com")
            .build()
            .await;

        assert!(config.is_ok());
        let config = config.unwrap();
        assert!(config.server_cert.is_some());
        assert!(config.server_identity.is_some());
        assert!(config.ca_cert.is_some());
        assert_eq!(config.server_name, Some("test.example.com".to_string()));
        assert!(config.require_client_cert);
    }

    #[tokio::test]
    async fn test_missing_required_files() {
        // Building with require_client_cert but no CA cert should fail
        let result = TlsConfig::builder()
            .require_client_cert(true)
            .build()
            .await;

        // Should fail during build due to missing CA cert for mutual TLS
        assert!(result.is_err());
        
        // Verify the error message
        if let Err(e) = result {
            assert!(e.to_string().contains("Mutual TLS requires CA certificate"));
        }
    }
}