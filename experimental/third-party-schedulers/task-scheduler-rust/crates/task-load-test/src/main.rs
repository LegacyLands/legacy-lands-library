use clap::Parser;
use hdrhistogram::Histogram;
use prometheus::{Counter, Gauge, HistogramVec, Registry};
use rand::{Rng, SeedableRng};
use rand::rngs::StdRng;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};
use uuid::Uuid;

// Generated protobuf code
mod proto {
    tonic::include_proto!("taskscheduler");
}

use proto::{task_scheduler_client::TaskSchedulerClient, TaskRequest, BatchTaskRequest};
use tokio::sync::Mutex;
use tokio::time::interval;
use tonic::transport::Channel;
use tonic::Request;
use tracing::{error, info, trace};

#[derive(Parser, Debug)]
#[command(author, version, about, long_about = None)]
struct Args {
    /// Target requests per second
    #[arg(long, default_value = "100")]
    rps: u64,

    /// Test duration in seconds
    #[arg(long, default_value = "60")]
    duration: u64,

    /// Number of concurrent connections
    #[arg(long, default_value = "10")]
    connections: usize,

    /// gRPC endpoint
    #[arg(long, default_value = "http://localhost:50051")]
    endpoint: String,

    /// Task methods to use (comma-separated)
    #[arg(long, default_value = "echo,add,multiply,concat")]
    methods: String,
    
    /// Include sleep tasks (adds realistic workload)
    #[arg(long)]
    include_sleep: bool,
    
    /// Sleep duration range in ms (min-max)
    #[arg(long, default_value = "10-100")]
    sleep_range: String,

    /// Payload size in bytes
    #[arg(long, default_value = "1024")]
    payload_size: usize,

    /// Enable metrics server
    #[arg(long)]
    metrics: bool,

    /// Metrics server address
    #[arg(long, default_value = "0.0.0.0:9090")]
    metrics_addr: String,
    
    /// Enable batch submission mode for extreme performance
    #[arg(long)]
    batch_mode: bool,
    
    /// Batch size (number of tasks per batch)
    #[arg(long, default_value = "100")]
    batch_size: usize,
    
}

struct LoadTestMetrics {
    requests_sent: Counter,
    requests_completed: Counter,
    requests_failed: Counter,
    active_requests: Gauge,
    response_time: HistogramVec,
}

impl LoadTestMetrics {
    fn new() -> Self {
        let requests_sent =
            Counter::new("load_test_requests_sent_total", "Total requests sent").unwrap();
        let requests_completed = Counter::new(
            "load_test_requests_completed_total",
            "Total requests completed successfully",
        )
        .unwrap();
        let requests_failed =
            Counter::new("load_test_requests_failed_total", "Total requests failed").unwrap();
        let active_requests =
            Gauge::new("load_test_active_requests", "Currently active requests").unwrap();
        let response_time = HistogramVec::new(
            prometheus::HistogramOpts::new(
                "load_test_response_time_seconds",
                "Response time distribution",
            )
            .buckets(vec![0.001, 0.005, 0.01, 0.05, 0.1, 0.5, 1.0]),
            &["method"],
        )
        .unwrap();

        Self {
            requests_sent,
            requests_completed,
            requests_failed,
            active_requests,
            response_time,
        }
    }

    fn register(&self, registry: &Registry) -> Result<(), prometheus::Error> {
        registry.register(Box::new(self.requests_sent.clone()))?;
        registry.register(Box::new(self.requests_completed.clone()))?;
        registry.register(Box::new(self.requests_failed.clone()))?;
        registry.register(Box::new(self.active_requests.clone()))?;
        registry.register(Box::new(self.response_time.clone()))?;
        Ok(())
    }
}

// Atomic counters for lock-free statistics
struct AtomicStats {
    total_sent: AtomicU64,
    total_completed: AtomicU64,
    total_failed: AtomicU64,
}

impl AtomicStats {
    fn new() -> Self {
        Self {
            total_sent: AtomicU64::new(0),
            total_completed: AtomicU64::new(0),
            total_failed: AtomicU64::new(0),
        }
    }
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    // Initialize tracing
    tracing_subscriber::fmt()
        .with_env_filter(tracing_subscriber::EnvFilter::from_default_env())
        .init();

    let args = Args::parse();

    info!(
        "Starting load test: {} RPS for {} seconds",
        args.rps, args.duration
    );
    info!("Target endpoint: {}", args.endpoint);
    info!("Concurrent connections: {}", args.connections);
    
    if args.batch_mode {
        info!("Batch mode enabled with batch size: {}", args.batch_size);
    }

    // Parse methods
    let mut methods: Vec<String> = args
        .methods
        .split(',')
        .map(|s| s.trim().to_string())
        .collect();
    
    // Add sleep tasks if requested
    if args.include_sleep {
        methods.push("sleep".to_string());
    }
    
    // Parse sleep range
    let sleep_range: (u64, u64) = if args.include_sleep {
        let parts: Vec<&str> = args.sleep_range.split('-').collect();
        if parts.len() == 2 {
            let min = parts[0].parse().unwrap_or(10);
            let max = parts[1].parse().unwrap_or(100);
            (min, max)
        } else {
            (10, 100)
        }
    } else {
        (10, 100)
    };

    // Always use bincode serialization
    info!("Using bincode serialization for all tasks");

    // Initialize metrics
    let registry = Registry::new();
    let metrics = Arc::new(LoadTestMetrics::new());
    metrics.register(&registry)?;

    // Start metrics server if enabled
    if args.metrics {
        let metrics_registry = registry.clone();
        let metrics_addr = args.metrics_addr.parse()?;
        tokio::spawn(async move {
            if let Err(e) = start_metrics_server(metrics_addr, metrics_registry).await {
                error!("Metrics server error: {}", e);
            }
        });
    }

    // Initialize atomic stats
    let stats = Arc::new(AtomicStats::new());
    
    // Thread-safe histogram with mutex (only for latency recording)
    let latency_histogram = Arc::new(Mutex::new(Histogram::<u64>::new(3).unwrap()));

    // Create workers with dedicated clients (no shared locks)
    let test_duration = Duration::from_secs(args.duration);
    let test_start = Instant::now();
    let workers_per_connection = 10; // Multiple workers per connection
    let total_workers = args.connections * workers_per_connection;
    
    // Calculate requests per worker
    let rps_per_worker = args.rps as f64 / total_workers as f64;
    let interval_ns = if rps_per_worker > 0.0 {
        (1_000_000_000.0 / rps_per_worker) as u64
    } else {
        0 // 0 means unlimited rate
    };

    let mut handles = Vec::new();

    for _conn_idx in 0..args.connections {
        // Create a dedicated client for this group of workers with optimized settings
        let channel = Channel::from_shared(args.endpoint.clone())?
            .connect_timeout(Duration::from_secs(30))
            .timeout(Duration::from_secs(60))
            .rate_limit(5000, Duration::from_secs(1)) // 5000 requests per second per connection
            .concurrency_limit(1000)
            .connect()
            .await?;
        let client = TaskSchedulerClient::new(channel);
        
        for _worker_idx in 0..workers_per_connection {
            let client = client.clone(); // Clone is cheap for gRPC clients
            let methods = methods.clone();
            let metrics = metrics.clone();
            let stats = stats.clone();
            let latency_histogram = latency_histogram.clone();
            let payload_size = args.payload_size;
            let batch_mode = args.batch_mode;
            let batch_size = args.batch_size;
            let worker_rps = rps_per_worker;
            let interval_nanos = interval_ns;
            
            let handle = tokio::spawn(async move {
                let mut ticker = if worker_rps > 0.0 {
                    Some(interval(Duration::from_nanos(interval_nanos)))
                } else {
                    None
                };
                let mut rng = StdRng::from_entropy();
                let mut local_client = client;
                let sleep_range = sleep_range;
                
                loop {
                    if test_start.elapsed() >= test_duration {
                        break;
                    }
                    
                    // Only use ticker if RPS is limited
                    if let Some(ref mut t) = ticker {
                        t.tick().await;
                    } else {
                        // In unlimited mode, yield occasionally to prevent starving other tasks
                        tokio::task::yield_now().await;
                    }
                    
                    if batch_mode {
                        // Batch mode: collect multiple tasks and submit together
                        let mut batch_tasks = Vec::with_capacity(batch_size);
                        
                        for _ in 0..batch_size {
                            let task = generate_task_request(&mut rng, &methods, payload_size, sleep_range);
                            batch_tasks.push(task);
                        }
                        
                        let batch_count = batch_tasks.len();
                        stats.total_sent.fetch_add(batch_count as u64, Ordering::Relaxed);
                        metrics.requests_sent.inc_by(batch_count as f64);
                        metrics.active_requests.add(batch_count as f64);
                        
                        let start = Instant::now();
                        
                        let request = Request::new(BatchTaskRequest {
                            tasks: batch_tasks,
                            is_async: true,
                        });
                        
                        trace!("Submitting batch of {} tasks", batch_count);
                        match local_client.batch_submit_tasks(request).await {
                            Ok(response) => {
                                let duration = start.elapsed();
                                let resp = response.into_inner();
                                let completed = resp.total_submitted as u64;
                                let failed = resp.total_failed as u64;
                                
                                stats.total_completed.fetch_add(completed, Ordering::Relaxed);
                                stats.total_failed.fetch_add(failed, Ordering::Relaxed);
                                metrics.requests_completed.inc_by(completed as f64);
                                metrics.requests_failed.inc_by(failed as f64);
                                
                                metrics
                                    .response_time
                                    .with_label_values(&["batch"])
                                    .observe(duration.as_secs_f64());
                                
                                // Record latency (this is the only place we need a lock)
                                if let Ok(mut hist) = latency_histogram.try_lock() {
                                    let _ = hist.record(duration.as_micros() as u64);
                                }
                            }
                            Err(e) => {
                                error!("Batch request failed: {}", e);
                                stats.total_failed.fetch_add(batch_count as u64, Ordering::Relaxed);
                                metrics.requests_failed.inc_by(batch_count as f64);
                            }
                        }
                        
                        metrics.active_requests.sub(batch_count as f64);
                    } else {
                        // Single task mode
                        let task = generate_task_request(&mut rng, &methods, payload_size, sleep_range);
                        let method = task.method.clone();
                        
                        stats.total_sent.fetch_add(1, Ordering::Relaxed);
                        metrics.requests_sent.inc();
                        metrics.active_requests.inc();
                        
                        let start = Instant::now();
                        
                        let request = Request::new(task);
                        
                        match local_client.submit_task(request).await {
                            Ok(_response) => {
                                let duration = start.elapsed();
                                stats.total_completed.fetch_add(1, Ordering::Relaxed);
                                metrics.requests_completed.inc();
                                metrics
                                    .response_time
                                    .with_label_values(&[&method])
                                    .observe(duration.as_secs_f64());
                                
                                // Record latency (this is the only place we need a lock)
                                if let Ok(mut hist) = latency_histogram.try_lock() {
                                    let _ = hist.record(duration.as_micros() as u64);
                                }
                            }
                            Err(e) => {
                                error!("Request failed: {}", e);
                                stats.total_failed.fetch_add(1, Ordering::Relaxed);
                                metrics.requests_failed.inc();
                            }
                        }
                        
                        metrics.active_requests.dec();
                    }
                }
            });
            
            handles.push(handle);
        }
    }

    info!("Started {} workers, generating load...", total_workers);

    // Wait for all workers to complete
    for handle in handles {
        let _ = handle.await;
    }

    info!("Load generation complete");

    // Print results
    print_results(&stats, &latency_histogram, test_start).await;

    Ok(())
}

async fn print_results(
    stats: &Arc<AtomicStats>,
    latency_histogram: &Arc<Mutex<Histogram<u64>>>,
    start_time: Instant,
) {
    let duration = start_time.elapsed();
    let total_sent = stats.total_sent.load(Ordering::Relaxed);
    let total_completed = stats.total_completed.load(Ordering::Relaxed);
    let total_failed = stats.total_failed.load(Ordering::Relaxed);

    println!("\n=== LOAD TEST RESULTS ===");
    println!("Duration: {:.2}s", duration.as_secs_f64());
    println!("Total sent: {}", total_sent);
    println!("Total completed: {}", total_completed);
    println!("Total failed: {}", total_failed);
    println!(
        "Success rate: {:.2}%",
        (total_completed as f64 / total_sent as f64) * 100.0
    );
    println!(
        "Actual RPS: {:.2}",
        total_sent as f64 / duration.as_secs_f64()
    );

    let hist = latency_histogram.lock().await;
    if !hist.is_empty() {
            println!("\nLatency (microseconds):");
            println!("  Min: {}", hist.min());
            println!("  P50: {}", hist.value_at_quantile(0.50));
            println!("  P90: {}", hist.value_at_quantile(0.90));
            println!("  P95: {}", hist.value_at_quantile(0.95));
            println!("  P99: {}", hist.value_at_quantile(0.99));
            println!("  P99.9: {}", hist.value_at_quantile(0.999));
            println!("  Max: {}", hist.max());
            println!("  Mean: {:.2}", hist.mean());
            println!("  StdDev: {:.2}", hist.stdev());
    }
}

fn generate_task_request(
    rng: &mut StdRng,
    methods: &[String],
    payload_size: usize,
    sleep_range: (u64, u64),
) -> TaskRequest {
    let method = &methods[rng.gen_range(0..methods.len())];
    let task_id = Uuid::new_v4().to_string();
    
    // Helper function to serialize value as JSON string
    let serialize_value = |value: &serde_json::Value| -> String {
        serde_json::to_string(value).unwrap()
    };
    
    // Generate appropriate arguments based on method
    let args = match method.as_str() {
        "echo" => {
            // Echo expects a string argument
            let payload = generate_payload(payload_size);
            vec![serialize_value(&serde_json::Value::String(payload))]
        }
        "add" | "multiply" => {
            // Math operations expect numeric arguments
            let num1 = rng.gen_range(1..100) as f64;
            let num2 = rng.gen_range(1..100) as f64;
            vec![
                serialize_value(&serde_json::Value::Number(
                    serde_json::Number::from_f64(num1).unwrap()
                )),
                serialize_value(&serde_json::Value::Number(
                    serde_json::Number::from_f64(num2).unwrap()
                )),
            ]
        }
        "concat" => {
            // Concat expects string arguments
            vec![
                serialize_value(&serde_json::Value::String("Hello".to_string())),
                serialize_value(&serde_json::Value::String(" World".to_string())),
            ]
        }
        "sleep" => {
            // Sleep task expects duration in milliseconds
            let duration = rng.gen_range(sleep_range.0..=sleep_range.1);
            vec![serialize_value(&serde_json::Value::Number(
                serde_json::Number::from(duration)
            ))]
        }
        _ => {
            // Default: send a simple string payload
            let payload = generate_payload(payload_size);
            vec![serialize_value(&serde_json::Value::String(payload))]
        }
    };
    
    TaskRequest {
        task_id,
        method: method.clone(),
        args,
        deps: vec![],
        is_async: true,
    }
}

fn generate_payload(size: usize) -> String {
    // Simple payload without JSON serialization overhead
    format!("{{\"data\":\"{}\"}}", "x".repeat(size))
}

async fn start_metrics_server(
    addr: std::net::SocketAddr,
    registry: Registry,
) -> Result<(), Box<dyn std::error::Error>> {
    use axum::{routing::get, Router};
    use prometheus::{Encoder, TextEncoder};

    let app = Router::new().route(
        "/metrics",
        get(move || async move {
            let encoder = TextEncoder::new();
            let metric_families = registry.gather();
            let mut buffer = Vec::new();
            encoder.encode(&metric_families, &mut buffer).unwrap();
            String::from_utf8(buffer).unwrap()
        }),
    );

    let listener = tokio::net::TcpListener::bind(addr).await?;
    info!("Metrics server listening on {}", addr);
    axum::serve(listener, app).await?;

    Ok(())
}