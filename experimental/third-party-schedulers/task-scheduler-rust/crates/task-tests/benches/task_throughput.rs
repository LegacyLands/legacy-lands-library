use criterion::{criterion_group, criterion_main, Criterion, BenchmarkId};
use task_common::models::{TaskInfo, TaskStatus, TaskMetadata};
use tokio::runtime::Runtime;
use std::time::Duration;
use uuid::Uuid;
use chrono::Utc;

fn create_test_task(name: &str) -> TaskInfo {
    TaskInfo {
        id: Uuid::new_v4(),
        method: format!("test.bench.{}", name),
        args: vec![serde_json::json!({"value": 42})],
        dependencies: vec![],
        priority: 5,
        metadata: TaskMetadata::default(),
        status: TaskStatus::Pending,
        created_at: Utc::now(),
        updated_at: Utc::now(),
    }
}

fn task_submission_benchmark(c: &mut Criterion) {
    let rt = Runtime::new().unwrap();
    
    let mut group = c.benchmark_group("task_submission");
    
    for size in [10, 100, 1000].iter() {
        group.bench_with_input(BenchmarkId::from_parameter(size), size, |b, &size| {
            b.to_async(&rt).iter(|| async move {
                // Simulate task submission
                let mut tasks = Vec::new();
                for i in 0..size {
                    let task = create_test_task(&format!("bench_task_{}", i));
                    tasks.push(task);
                }
                
                // Simulate processing
                for task in tasks {
                    // In a real benchmark, we would submit to a queue manager
                    let _ = serde_json::to_string(&task).unwrap();
                }
            });
        });
    }
    
    group.finish();
}

fn task_processing_benchmark(c: &mut Criterion) {
    let rt = Runtime::new().unwrap();
    
    let mut group = c.benchmark_group("task_processing");
    group.measurement_time(Duration::from_secs(10));
    
    group.bench_function("sequential_processing", |b| {
        b.to_async(&rt).iter(|| async {
            // Simulate sequential task processing
            for i in 0..100 {
                let task = create_test_task(&format!("process_task_{}", i));
                // Simulate task execution
                tokio::time::sleep(Duration::from_micros(10)).await;
                let _ = serde_json::to_string(&task).unwrap();
            }
        });
    });
    
    group.bench_function("concurrent_processing", |b| {
        b.to_async(&rt).iter(|| async {
            // Simulate concurrent task processing
            let mut handles = vec![];
            for i in 0..100 {
                let task = create_test_task(&format!("concurrent_task_{}", i));
                let handle = tokio::spawn(async move {
                    tokio::time::sleep(Duration::from_micros(10)).await;
                    serde_json::to_string(&task).unwrap()
                });
                handles.push(handle);
            }
            
            // Wait for all tasks
            for handle in handles {
                let _ = handle.await;
            }
        });
    });
    
    group.finish();
}

fn dependency_resolution_benchmark(c: &mut Criterion) {
    let rt = Runtime::new().unwrap();
    
    let mut group = c.benchmark_group("dependency_resolution");
    
    group.bench_function("chain_dependencies", |b| {
        b.to_async(&rt).iter(|| async {
            // Simulate a chain of dependent tasks
            let mut tasks = Vec::new();
            let mut prev_id = None;
            
            for i in 0..10 {
                let mut task = create_test_task(&format!("chain_task_{}", i));
                if let Some(dep_id) = prev_id {
                    task.dependencies.push(dep_id);
                }
                prev_id = Some(task.id);
                tasks.push(task);
            }
            
            // Simulate dependency resolution
            for task in tasks {
                let _ = serde_json::to_string(&task).unwrap();
            }
        });
    });
    
    group.bench_function("fan_out_dependencies", |b| {
        b.to_async(&rt).iter(|| async {
            // Simulate fan-out dependencies
            let root_task = create_test_task("root_task");
            let root_id = root_task.id;
            let mut tasks = vec![root_task];
            
            for i in 0..10 {
                let mut task = create_test_task(&format!("fan_out_task_{}", i));
                task.dependencies.push(root_id);
                tasks.push(task);
            }
            
            // Simulate dependency resolution
            for task in tasks {
                let _ = serde_json::to_string(&task).unwrap();
            }
        });
    });
    
    group.finish();
}

criterion_group!(
    benches,
    task_submission_benchmark,
    task_processing_benchmark,
    dependency_resolution_benchmark
);
criterion_main!(benches);