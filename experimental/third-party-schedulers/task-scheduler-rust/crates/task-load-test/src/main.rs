use clap::Parser;
use hdrhistogram::Histogram;
use prometheus::{Counter, Gauge, HistogramVec, Registry};
use prost_types::Any;
use rand::Rng;
use std::sync::Arc;
use std::time::{Duration, Instant};

// Generated protobuf code
mod proto {
    tonic::include_proto!("taskscheduler");
}

use proto::{task_scheduler_client::TaskSchedulerClient, TaskRequest};
use tokio::sync::RwLock;
use tokio::time::interval;
use tonic::Request;
use tracing::{error, info};

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
    #[arg(long, default_value = "test.echo,test.compute,test.sleep")]
    methods: String,

    /// Payload size in bytes
    #[arg(long, default_value = "1024")]
    payload_size: usize,

    /// Enable metrics server
    #[arg(long)]
    metrics: bool,

    /// Metrics server address
    #[arg(long, default_value = "0.0.0.0:9090")]
    metrics_addr: String,
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
            .buckets(vec![0.01, 0.05, 0.1, 0.5, 1.0, 2.0, 5.0, 10.0]),
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

struct LoadTestStats {
    latencies: Histogram<u64>,
    start_time: Instant,
    total_sent: u64,
    total_completed: u64,
    total_failed: u64,
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

    // Parse methods
    let methods: Vec<String> = args
        .methods
        .split(',')
        .map(|s| s.trim().to_string())
        .collect();

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

    // Initialize stats
    let stats = Arc::new(RwLock::new(LoadTestStats {
        latencies: Histogram::<u64>::new(3).unwrap(),
        start_time: Instant::now(),
        total_sent: 0,
        total_completed: 0,
        total_failed: 0,
    }));

    // Create client pool
    let mut clients = Vec::new();
    for _ in 0..args.connections {
        let client: TaskSchedulerClient<tonic::transport::Channel> =
            TaskSchedulerClient::connect(args.endpoint.clone()).await?;
        clients.push(Arc::new(tokio::sync::Mutex::new(client)));
    }

    // Start load generation
    let interval_ns = 1_000_000_000u64 / args.rps;
    let mut ticker = interval(Duration::from_nanos(interval_ns));

    let test_duration = Duration::from_secs(args.duration);
    let test_start = Instant::now();

    // Spawn workers
    let (tx, rx) = tokio::sync::mpsc::unbounded_channel();
    let rx = Arc::new(tokio::sync::Mutex::new(rx));

    for (_i, client) in clients.into_iter().enumerate() {
        let _tx = tx.clone();
        let rx = rx.clone();
        let methods = methods.clone();
        let metrics = metrics.clone();
        let stats = stats.clone();
        let payload_size = args.payload_size;

        tokio::spawn(async move {
            loop {
                let msg = {
                    let mut rx_guard = rx.lock().await;
                    rx_guard.recv().await
                };

                if msg.is_none() {
                    break;
                }
                let method = methods[rand::thread_rng().gen_range(0..methods.len())].clone();
                let payload = generate_payload(payload_size);
                let task_id = uuid::Uuid::new_v4().to_string();

                let start = Instant::now();
                metrics.active_requests.inc();

                let mut client = client.lock().await;
                let request = Request::new(TaskRequest {
                    task_id: task_id.clone(),
                    method: method.clone(),
                    args: vec![Any {
                        type_url: "type.googleapis.com/google.protobuf.StringValue".to_string(),
                        value: payload.into_bytes(),
                    }],
                    deps: vec![],
                    is_async: true,
                });

                match client.submit_task(request).await {
                    Ok(_response) => {
                        let duration = start.elapsed();
                        metrics.requests_completed.inc();
                        metrics
                            .response_time
                            .with_label_values(&[&method])
                            .observe(duration.as_secs_f64());

                        let mut stats = stats.write().await;
                        stats.total_completed += 1;
                        stats.latencies.record(duration.as_micros() as u64).unwrap();
                    }
                    Err(e) => {
                        error!("Request failed: {}", e);
                        metrics.requests_failed.inc();

                        let mut stats = stats.write().await;
                        stats.total_failed += 1;
                    }
                }

                metrics.active_requests.dec();
            }
        });
    }

    // Generate load
    info!("Starting load generation...");

    loop {
        if test_start.elapsed() >= test_duration {
            break;
        }

        ticker.tick().await;

        // Send work to a random worker
        if tx.send(()).is_ok() {
            metrics.requests_sent.inc();
            let mut stats = stats.write().await;
            stats.total_sent += 1;
        }
    }

    drop(tx);
    info!("Load generation complete, waiting for in-flight requests...");

    // Wait for in-flight requests
    tokio::time::sleep(Duration::from_secs(5)).await;

    // Print results
    print_results(&stats).await;

    Ok(())
}

async fn print_results(stats: &Arc<RwLock<LoadTestStats>>) {
    let stats = stats.read().await;
    let duration = stats.start_time.elapsed();

    println!("\n=== LOAD TEST RESULTS ===");
    println!("Duration: {:.2}s", duration.as_secs_f64());
    println!("Total sent: {}", stats.total_sent);
    println!("Total completed: {}", stats.total_completed);
    println!("Total failed: {}", stats.total_failed);
    println!(
        "Success rate: {:.2}%",
        (stats.total_completed as f64 / stats.total_sent as f64) * 100.0
    );
    println!(
        "Actual RPS: {:.2}",
        stats.total_sent as f64 / duration.as_secs_f64()
    );

    if !stats.latencies.is_empty() {
        println!("\nLatency (microseconds):");
        println!("  Min: {}", stats.latencies.min());
        println!("  P50: {}", stats.latencies.value_at_quantile(0.50));
        println!("  P90: {}", stats.latencies.value_at_quantile(0.90));
        println!("  P95: {}", stats.latencies.value_at_quantile(0.95));
        println!("  P99: {}", stats.latencies.value_at_quantile(0.99));
        println!("  P99.9: {}", stats.latencies.value_at_quantile(0.999));
        println!("  Max: {}", stats.latencies.max());
        println!("  Mean: {:.2}", stats.latencies.mean());
        println!("  StdDev: {:.2}", stats.latencies.stdev());
    }
}

fn generate_payload(size: usize) -> String {
    serde_json::json!({
        "data": "x".repeat(size),
        "timestamp": chrono::Utc::now().to_rfc3339(),
        "load_test": true,
    })
    .to_string()
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
