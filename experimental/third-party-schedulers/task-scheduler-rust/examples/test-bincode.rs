use serde_json::json;
use base64::Engine;

fn main() {
    // Test how bincode serializes serde_json::Value
    let num_value = json!(42.0);
    let str_value = json!("Hello");
    
    println!("Testing bincode serialization of serde_json::Value:");
    
    // Serialize number
    match bincode::serialize(&num_value) {
        Ok(bytes) => {
            println!("Number (42.0) serialized to {} bytes: {:?}", bytes.len(), bytes);
            let encoded = base64::engine::general_purpose::STANDARD.encode(&bytes);
            println!("Base64: {}", encoded);
            
            // Try to deserialize
            match bincode::deserialize::<serde_json::Value>(&bytes) {
                Ok(v) => println!("Deserialized back to: {:?}", v),
                Err(e) => println!("Deserialize error: {}", e),
            }
        }
        Err(e) => println!("Serialize error: {}", e),
    }
    
    println!();
    
    // Serialize string
    match bincode::serialize(&str_value) {
        Ok(bytes) => {
            println!("String (\"Hello\") serialized to {} bytes: {:?}", bytes.len(), bytes);
            let encoded = base64::engine::general_purpose::STANDARD.encode(&bytes);
            println!("Base64: {}", encoded);
        }
        Err(e) => println!("Serialize error: {}", e),
    }
}