use futures::StreamExt;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    // Connect to NATS
    let client = async_nats::connect("nats://localhost:4222").await?;
    
    println!("Connected to NATS, subscribing to *.events.*");
    
    // Subscribe to all events
    let mut subscriber = client.subscribe("*.events.*".to_string()).await?;
    
    println!("Listening for events...");
    
    // Listen for messages
    while let Some(msg) = subscriber.next().await {
        println!("\n=== Received Message ===");
        println!("Subject: {}", msg.subject);
        println!("Payload length: {} bytes", msg.payload.len());
        
        // Try to parse as string
        match std::str::from_utf8(&msg.payload) {
            Ok(str_payload) => {
                println!("Payload (as string): {}", str_payload);
                
                // Try to parse as JSON
                match serde_json::from_str::<serde_json::Value>(str_payload) {
                    Ok(json) => {
                        println!("Payload (as JSON): {}", serde_json::to_string_pretty(&json)?);
                    }
                    Err(e) => {
                        println!("Failed to parse as JSON: {}", e);
                    }
                }
            }
            Err(_) => {
                println!("Payload (as hex): {:02x?}", &msg.payload);
            }
        }
    }
    
    Ok(())
}