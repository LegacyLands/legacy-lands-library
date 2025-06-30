//! High-performance serialization utilities using bincode
//! 
//! This module provides fast binary serialization for all task system components.

use serde::{Deserialize, Serialize};
use crate::error::{TaskError, TaskResult};
use base64::{Engine as _, engine::general_purpose::STANDARD as BASE64};

/// Trait for types that can be serialized/deserialized efficiently
pub trait TaskSerializable: Serialize + for<'de> Deserialize<'de> + Send + Sync {
    /// Serialize to bincode bytes
    fn serialize_to_bytes(&self) -> TaskResult<Vec<u8>> {
        bincode::serialize(self)
            .map_err(|e| TaskError::SerializationError(format!("Bincode serialization failed: {}", e)))
    }
    
    /// Deserialize from bincode bytes
    fn deserialize_from_bytes(data: &[u8]) -> TaskResult<Self> {
        bincode::deserialize(data)
            .map_err(|e| TaskError::SerializationError(format!("Bincode deserialization failed: {}", e)))
    }
    
    /// Serialize to base64-encoded string for transport
    fn serialize_to_base64(&self) -> TaskResult<String> {
        let bytes = self.serialize_to_bytes()?;
        Ok(BASE64.encode(bytes))
    }
    
    /// Deserialize from base64-encoded string
    fn deserialize_from_base64(encoded: &str) -> TaskResult<Self> {
        let bytes = BASE64.decode(encoded)
            .map_err(|e| TaskError::SerializationError(format!("Base64 decode failed: {}", e)))?;
        Self::deserialize_from_bytes(&bytes)
    }
}

/// Auto-implement TaskSerializable for all types that implement Serialize + Deserialize
impl<T> TaskSerializable for T where T: Serialize + for<'de> Deserialize<'de> + Send + Sync {}

#[cfg(test)]
mod tests {
    use super::*;
    use serde::{Deserialize, Serialize};
    
    #[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
    struct TestData {
        id: u64,
        name: String,
        values: Vec<f64>,
    }
    
    #[test]
    fn test_bincode_serialization() {
        let data = TestData {
            id: 42,
            name: "test".to_string(),
            values: vec![1.0, 2.0, 3.0],
        };
        
        let bytes = data.serialize_to_bytes().unwrap();
        let decoded: TestData = TestData::deserialize_from_bytes(&bytes).unwrap();
        
        assert_eq!(data, decoded);
    }
    
    #[test]
    fn test_base64_serialization() {
        let data = TestData {
            id: 42,
            name: "test".to_string(),
            values: vec![1.0, 2.0, 3.0],
        };
        
        let encoded = data.serialize_to_base64().unwrap();
        let decoded: TestData = TestData::deserialize_from_base64(&encoded).unwrap();
        
        assert_eq!(data, decoded);
        
        // Base64 should be longer than raw bytes
        let raw_bytes = data.serialize_to_bytes().unwrap();
        assert!(encoded.len() > raw_bytes.len());
    }
    
    #[test]
    fn test_bincode_efficiency() {
        let data = TestData {
            id: 42,
            name: "test".to_string(),
            values: vec![1.0, 2.0, 3.0, 4.0, 5.0],
        };
        
        let bytes = data.serialize_to_bytes().unwrap();
        
        // Bincode should be very compact
        // 8 bytes for id + string length + string data + vec length + 5 * 8 bytes for f64s
        println!("Bincode size: {} bytes", bytes.len());
        assert!(bytes.len() < 100); // Should be much smaller than JSON equivalent
    }
}