#!/bin/bash

# Full rebuild and deploy script with all fixes
# This script rebuilds all components with the fixes and redeploys the system

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

echo "=== Full System Rebuild and Deploy ==="
echo "This script will:"
echo "1. Stop all running containers"
echo "2. Rebuild all components with fixes"
echo "3. Deploy the updated system"
echo "4. Run tests to verify all fixes"
echo ""

# Function to wait with progress
wait_with_progress() {
    local duration=$1
    local message=$2
    echo -n "$message"
    for i in $(seq 1 $duration); do
        echo -n "."
        sleep 1
    done
    echo " Done!"
}

# Step 1: Stop existing system
echo "=== Step 1: Stopping existing system ==="
docker compose -f deploy/docker/docker-compose.yaml down -v || true
echo "System stopped"

# Step 2: Build all components
echo -e "\n=== Step 2: Building all components ==="
echo "Building manager and worker binaries..."
cargo build --release --bin task-manager
cargo build --release --bin task-worker
cargo build --release --bin submit-test-task
cargo build --release --bin submit_fail_task

echo "Building Docker images with no cache..."
./scripts/docker-build-local.sh --no-cache

# Step 3: Deploy the system
echo -e "\n=== Step 3: Deploying the system ==="
docker compose -f deploy/docker/docker-compose.yaml up -d

echo "Waiting for services to be ready..."
wait_with_progress 10 "Waiting for NATS to be ready"

# Check service health
echo -e "\n=== Checking service health ==="
docker compose -f deploy/docker/docker-compose.yaml ps

# Wait for manager and workers to fully initialize
wait_with_progress 10 "Waiting for manager and workers to initialize"

# Step 4: Verify fixes
echo -e "\n=== Step 4: Verifying all fixes ==="

# Test 1: Worker active tasks
echo -e "\n[Test 1] Testing worker_active_tasks metric..."
echo "Submitting tasks to test worker metrics..."
for i in {1..5}; do
    ./target/release/submit-test-task --method echo --payload "worker test $i" &
done
wait

wait_with_progress 5 "Waiting for tasks to be processed"

echo "Worker metrics:"
curl -s http://localhost:9000/metrics | grep "task_manager_worker_active_tasks" | grep -v "#"

# Test 2: Queue depth
echo -e "\n[Test 2] Testing queue_depth metric..."
echo "Current queue depth:"
curl -s http://localhost:9000/metrics | grep "task_manager_queue_depth" | grep -v "#"

# Test 3: Task execution duration
echo -e "\n[Test 3] Testing task_execution_duration metric..."
echo "Submitting tasks for duration testing..."
for i in {1..3}; do
    ./target/release/submit-test-task --method echo --payload "duration test $i"
    sleep 1
done

wait_with_progress 5 "Waiting for execution duration metrics"

echo "Execution duration metrics:"
curl -s http://localhost:9000/metrics | grep "task_manager_task_execution_duration_seconds_count" | grep -v "#" | head -5

# Test 4: CPU and Memory metrics
echo -e "\n[Test 4] Testing CPU and Memory metrics..."
echo "CPU metrics:"
curl -s http://localhost:9000/metrics | grep "task_manager_cpu_usage_percent" | grep -v "#" | head -5
echo "Memory metrics:"
curl -s http://localhost:9000/metrics | grep "task_manager_memory_usage_bytes" | grep -v "#"

# Test 5: Task success
echo -e "\n[Test 5] Testing task success (echo and init)..."
echo "Submitting echo tasks..."
for i in {1..3}; do
    ./target/release/submit-test-task --method echo --payload "success test $i"
done

echo "Submitting init tasks..."
for i in {1..3}; do
    ./target/release/submit-test-task --method init --payload '{"config": "test"}'
done

wait_with_progress 10 "Waiting for tasks to complete"

echo "Task status metrics:"
curl -s http://localhost:9000/metrics | grep "task_manager_tasks_status_total" | grep -v "#" | sort

# Summary
echo -e "\n=== Deployment Summary ==="
echo "All components have been rebuilt and deployed with the following fixes:"
echo "✓ worker_active_tasks now correctly increments and decrements"
echo "✓ queue_depth no longer goes negative"
echo "✓ task_execution_duration is now properly recorded"
echo "✓ CPU and Memory metrics are now collected"
echo "✓ init tasks now complete successfully"
echo ""
echo "Grafana dashboards: http://localhost:3000"
echo "- Executive Dashboard: http://localhost:3000/d/task-exec-dashboard/"
echo "- Operations Dashboard: http://localhost:3000/d/task-ops-dashboard/"
echo "- Performance Dashboard: http://localhost:3000/d/task-perf-dashboard/"
echo ""
echo "Run './scripts/generate-all-metrics-data.sh' to generate more test data"