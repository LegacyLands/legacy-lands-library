use std::time::{Duration, Instant};
use std::sync::Arc;
use tokio::sync::Mutex;
use hdrhistogram::Histogram;

mod common;
use common::TestEnvironment;

struct PerformanceMetrics {
    total_requests: u64,
    successful_requests: u64,
    failed_requests: u64,
    latency_histogram: Histogram<u64>,
}

impl PerformanceMetrics {
    fn new() -> Self {
        Self {
            total_requests: 0,
            successful_requests: 0,
            failed_requests: 0,
            latency_histogram: Histogram::<u64>::new(3).unwrap(),
        }
    }
    
    fn record_request(&mut self, duration: Duration, success: bool) {
        self.total_requests += 1;
        if success {
            self.successful_requests += 1;
        } else {
            self.failed_requests += 1;
        }
        self.latency_histogram.record(duration.as_micros() as u64).unwrap();
    }
    
    fn print_summary(&self, test_duration: Duration) {
        println!("\n=== Performance Test Results ===");
        println!("Test Duration: {:?}", test_duration);
        println!("Total Requests: {}", self.total_requests);
        println!("Successful Requests: {}", self.successful_requests);
        println!("Failed Requests: {}", self.failed_requests);
        println!("Success Rate: {:.2}%", 
            (self.successful_requests as f64 / self.total_requests as f64) * 100.0);
        println!("Throughput: {:.2} req/s", 
            self.total_requests as f64 / test_duration.as_secs_f64());
        
        println!("\nLatency Statistics (microseconds):");
        println!("  Min: {}", self.latency_histogram.min());
        println!("  Max: {}", self.latency_histogram.max());
        println!("  Mean: {:.2}", self.latency_histogram.mean());
        println!("  P50: {}", self.latency_histogram.value_at_percentile(50.0));
        println!("  P90: {}", self.latency_histogram.value_at_percentile(90.0));
        println!("  P95: {}", self.latency_histogram.value_at_percentile(95.0));
        println!("  P99: {}", self.latency_histogram.value_at_percentile(99.0));
        println!("  P99.9: {}", self.latency_histogram.value_at_percentile(99.9));
    }
}

#[tokio::test]
#[ignore] // Run with: cargo test --test performance_test -- --ignored
async fn test_throughput_single_client() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let env = TestEnvironment::new().await?;
    let mut client = env.create_grpc_client().await?;
    let metrics = Arc::new(Mutex::new(PerformanceMetrics::new()));
    
    let test_duration = Duration::from_secs(30);
    let start = Instant::now();
    
    println!("Running single client throughput test for {:?}...", test_duration);
    
    while start.elapsed() < test_duration {
        let req_start = Instant::now();
        let result = client.submit_echo_task("Performance test payload").await;
        let duration = req_start.elapsed();
        
        let mut m = metrics.lock().await;
        m.record_request(duration, result.is_ok());
    }
    
    let total_duration = start.elapsed();
    let metrics = metrics.lock().await;
    metrics.print_summary(total_duration);
    
    // Assert minimum performance requirements
    assert!(metrics.successful_requests > 0);
    assert!(metrics.latency_histogram.mean() < 50_000.0); // < 50ms average
    
    Ok(())
}

#[tokio::test]
#[ignore]
async fn test_concurrent_clients() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let _env = TestEnvironment::new().await?;
    let metrics = Arc::new(Mutex::new(PerformanceMetrics::new()));
    
    let client_count = 10;
    let _test_duration = Duration::from_secs(30);
    let requests_per_client = 100;
    
    println!("Running {} concurrent clients test...", client_count);
    
    let start = Instant::now();
    let mut handles = vec![];
    
    for i in 0..client_count {
        let metrics = metrics.clone();
        let env_clone = TestEnvironment::new().await?;
        
        let handle = tokio::spawn(async move {
            let mut client = env_clone.create_grpc_client().await
                .map_err(|e| format!("Failed to create client: {}", e))?;
            
            for j in 0..requests_per_client {
                let req_start = Instant::now();
                let payload = format!("Client {} Request {}", i, j);
                let result = client.submit_echo_task(&payload).await;
                let duration = req_start.elapsed();
                
                let mut m = metrics.lock().await;
                m.record_request(duration, result.is_ok());
                
                // Small delay between requests
                tokio::time::sleep(Duration::from_millis(100)).await;
            }
            
            Ok::<(), String>(())
        });
        
        handles.push(handle);
    }
    
    // Wait for all clients to complete
    for handle in handles {
        handle.await?.map_err(|e: String| -> Box<dyn std::error::Error + Send + Sync> { Box::new(std::io::Error::new(std::io::ErrorKind::Other, e)) })?;
    }
    
    let total_duration = start.elapsed();
    let metrics = metrics.lock().await;
    metrics.print_summary(total_duration);
    
    // Assert performance requirements
    let success_rate = metrics.successful_requests as f64 / metrics.total_requests as f64;
    assert!(success_rate > 0.95); // > 95% success rate
    assert!(metrics.latency_histogram.value_at_percentile(95.0) < 100_000); // P95 < 100ms
    
    Ok(())
}

#[tokio::test]
#[ignore]
async fn test_sustained_load() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let env = TestEnvironment::new().await?;
    let metrics = Arc::new(Mutex::new(PerformanceMetrics::new()));
    
    let target_rps = 100;
    let test_duration = Duration::from_secs(60);
    let interval = Duration::from_millis(1000 / target_rps);
    
    println!("Running sustained load test at {} RPS for {:?}...", target_rps, test_duration);
    
    let start = Instant::now();
    let mut interval_timer = tokio::time::interval(interval);
    
    while start.elapsed() < test_duration {
        interval_timer.tick().await;
        
        let metrics = metrics.clone();
        let mut client = env.create_grpc_client().await?;
        
        tokio::spawn(async move {
            let req_start = Instant::now();
            let result = client.submit_computation_task(42, 58).await;
            let duration = req_start.elapsed();
            
            let mut m = metrics.lock().await;
            m.record_request(duration, result.is_ok());
        });
    }
    
    // Wait for in-flight requests to complete
    tokio::time::sleep(Duration::from_secs(5)).await;
    
    let total_duration = start.elapsed();
    let metrics = metrics.lock().await;
    metrics.print_summary(total_duration);
    
    // Verify sustained performance
    let actual_rps = metrics.total_requests as f64 / test_duration.as_secs_f64();
    assert!(actual_rps > target_rps as f64 * 0.9); // Within 10% of target RPS
    
    Ok(())
}

#[tokio::test]
#[ignore]
async fn test_burst_load() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let env = TestEnvironment::new().await?;
    let metrics = Arc::new(Mutex::new(PerformanceMetrics::new()));
    
    let burst_size = 100;
    let burst_count = 5;
    let delay_between_bursts = Duration::from_secs(5);
    
    println!("Running burst load test: {} bursts of {} requests...", burst_count, burst_size);
    
    for burst_num in 0..burst_count {
        println!("Burst {} starting...", burst_num + 1);
        let burst_start = Instant::now();
        
        let mut handles = vec![];
        for i in 0..burst_size {
            let metrics = metrics.clone();
            let mut client = env.create_grpc_client().await?;
            
            let handle = tokio::spawn(async move {
                let req_start = Instant::now();
                let result = client.submit_echo_task(&format!("Burst request {}", i)).await;
                let duration = req_start.elapsed();
                
                let mut m = metrics.lock().await;
                m.record_request(duration, result.is_ok());
            });
            
            handles.push(handle);
        }
        
        // Wait for burst to complete
        for handle in handles {
            handle.await?;
        }
        
        println!("Burst {} completed in {:?}", burst_num + 1, burst_start.elapsed());
        
        if burst_num < burst_count - 1 {
            tokio::time::sleep(delay_between_bursts).await;
        }
    }
    
    let metrics = metrics.lock().await;
    metrics.print_summary(Duration::from_secs(0)); // Duration not meaningful for burst test
    
    // Verify burst handling
    assert_eq!(metrics.total_requests, (burst_size * burst_count) as u64);
    assert!(metrics.successful_requests as f64 / metrics.total_requests as f64 > 0.9);
    
    Ok(())
}

#[tokio::test]
#[ignore]
async fn test_mixed_workload() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let _env = TestEnvironment::new().await?;
    let metrics = Arc::new(Mutex::new(PerformanceMetrics::new()));
    
    let test_duration = Duration::from_secs(60);
    let start = Instant::now();
    
    println!("Running mixed workload test for {:?}...", test_duration);
    
    // Spawn different types of workload
    let mut handles = vec![];
    
    // Fast echo tasks
    let metrics_clone = metrics.clone();
    handles.push(tokio::spawn(async move {
        let mut client = TestEnvironment::new().await?.create_grpc_client().await?;
        while start.elapsed() < test_duration {
            let req_start = Instant::now();
            let result = client.submit_echo_task("Fast task").await;
            let duration = req_start.elapsed();
            
            let mut m = metrics_clone.lock().await;
            m.record_request(duration, result.is_ok());
            
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
        Ok::<(), Box<dyn std::error::Error + Send + Sync>>(())
    }));
    
    // Computation tasks
    let metrics_clone = metrics.clone();
    handles.push(tokio::spawn(async move {
        let mut client = TestEnvironment::new().await?.create_grpc_client().await?;
        while start.elapsed() < test_duration {
            let req_start = Instant::now();
            let result = client.submit_computation_task(100, 200).await;
            let duration = req_start.elapsed();
            
            let mut m = metrics_clone.lock().await;
            m.record_request(duration, result.is_ok());
            
            tokio::time::sleep(Duration::from_millis(200)).await;
        }
        Ok::<(), Box<dyn std::error::Error + Send + Sync>>(())
    }));
    
    // Sleep tasks (simulating long-running operations)
    let metrics_clone = metrics.clone();
    handles.push(tokio::spawn(async move {
        let mut client = TestEnvironment::new().await?.create_grpc_client().await?;
        while start.elapsed() < test_duration {
            let req_start = Instant::now();
            let result = client.submit_async_sleep_task(1).await;
            let duration = req_start.elapsed();
            
            let mut m = metrics_clone.lock().await;
            m.record_request(duration, result.is_ok());
            
            tokio::time::sleep(Duration::from_secs(2)).await;
        }
        Ok::<(), Box<dyn std::error::Error + Send + Sync>>(())
    }));
    
    // Wait for all workloads to complete
    for handle in handles {
        handle.await??;
    }
    
    let total_duration = start.elapsed();
    let metrics = metrics.lock().await;
    metrics.print_summary(total_duration);
    
    // Verify mixed workload performance
    assert!(metrics.successful_requests > 0);
    assert!(metrics.latency_histogram.mean() < 200_000.0); // < 200ms average for mixed workload
    
    Ok(())
}