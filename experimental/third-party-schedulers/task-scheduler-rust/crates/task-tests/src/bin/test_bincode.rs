use base64::Engine;
use serde_json::json;

fn main() {
    println!("=== Testing bincode serialization of serde_json::Value ===\n");
    
    // Test 1: How bincode serializes a number
    let num = json!(42.0);
    let bytes = bincode::serialize(&num).unwrap();
    let encoded = base64::engine::general_purpose::STANDARD.encode(&bytes);
    println!("Number 42.0:");
    println!("  Bincode bytes: {:02x?}", bytes);
    println!("  Base64: {}", encoded);
    
    // Deserialize back
    let decoded = base64::engine::general_purpose::STANDARD.decode(&encoded).unwrap();
    let value: serde_json::Value = bincode::deserialize(&decoded).unwrap();
    println!("  Deserialized: {:?}\n", value);
    
    // Test 2: Decode actual base64 from Worker logs
    let worker_base64 = "DAAAAAAAAABBQUFBQUFBQUFFQT0=";
    println!("Base64 from Worker logs: {}", worker_base64);
    
    match base64::engine::general_purpose::STANDARD.decode(worker_base64) {
        Ok(bytes) => {
            println!("  Decoded to {} bytes: {:02x?}", bytes.len(), bytes);
            
            // Check if it's double-encoded (base64 within bincode)
            if bytes.len() > 8 {
                if let Ok(inner_str) = std::str::from_utf8(&bytes[8..]) {
                    println!("  Contains string after bincode header: {}", inner_str);
                    
                    // Try decoding the inner base64
                    if let Ok(inner_bytes) = base64::engine::general_purpose::STANDARD.decode(inner_str.trim_end_matches('=')) {
                        println!("  Inner base64 decoded to: {:02x?}", inner_bytes);
                        
                        // Try interpreting as f64
                        if inner_bytes.len() == 8 {
                            let num = f64::from_le_bytes(inner_bytes.try_into().unwrap());
                            println!("  As f64: {}", num);
                        }
                    }
                }
            }
            
            // Try direct bincode deserialization
            match bincode::deserialize::<serde_json::Value>(&bytes) {
                Ok(v) => println!("  Bincode deserialized to: {:?}", v),
                Err(e) => println!("  Bincode deserialize error: {}", e),
            }
        }
        Err(e) => println!("  Base64 decode error: {}", e),
    }
}