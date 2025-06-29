#!/bin/bash

# UltraThink Comprehensive Metrics Generation Script
# Merged from generate-all-metrics-data.sh and generate-metrics-ultrathink.sh
# This script generates comprehensive test data for all Grafana dashboard panels

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

echo "=== UltraThink Comprehensive Metrics Generation Script ==="
echo "Date: $(date)"
echo "This script will generate data for all Grafana dashboard panels"
echo ""

# Function to wait with progress indicator
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

# 0. Display current system status
echo "=== Current System Status ==="
echo "Queue depth:"
curl -s http://localhost:9000/metrics | grep "task_manager_queue_depth" | grep -v "#"
echo ""

# 1. Long-Running Tasks for Worker Utilization
echo "=== Phase 1: Long-Running Tasks for Worker Utilization ==="
echo "Submitting 10 sleep tasks (3 seconds each) to demonstrate worker utilization..."
for i in {1..10}; do
    ./target/release/submit-test-task --method sleep --payload "3000" &
    if [ $((i % 3)) -eq 0 ]; then
        sleep 0.5
    fi
done
wait

echo ""
echo "Checking worker utilization during sleep task execution:"
curl -s http://localhost:9000/metrics | grep "worker_active_tasks" | grep -v "^#" | grep -v "placeholder"
echo ""

# 2. Successful Tasks with Various Methods
echo "=== Phase 2: Successful Tasks with Various Methods ==="

# Echo tasks
echo "Submitting 20 echo tasks..."
for i in {1..20}; do
    ./target/release/submit-test-task --method echo --payload "Hello from task $i" &
    if [ $((i % 5)) -eq 0 ]; then
        wait
        sleep 0.1
    fi
done
wait

# Init tasks
echo "Submitting 10 init tasks..."
for i in {1..10}; do
    ./target/release/submit-test-task --method init --payload "configuration-$i" &
done
wait

# Math tasks
echo "Submitting math tasks..."
./target/release/submit-test-task --method add --payload '5,3'
./target/release/submit-test-task --method multiply --payload '4,7'

# String manipulation tasks
echo "Submitting string manipulation tasks..."
./target/release/submit-test-task --method uppercase --payload "hello world"
./target/release/submit-test-task --method lowercase --payload "HELLO WORLD"
./target/release/submit-test-task --method concat --payload "Hello, ,World"

# 3. Failed Tasks with All Error Categories
echo -e "\n=== Phase 3: Failed Tasks with All Error Categories ==="

# Standard error types
for error_type in timeout network validation permission resource database concurrency; do
    echo "Generating $error_type errors (5 tasks)..."
    for i in {1..5}; do
        ./target/release/submit-test-task --method fail --payload "$error_type" &
    done
    wait
done

# Unsupported method errors
echo "Generating unsupported method errors (5 tasks)..."
for method in nonexistent invalid_method not_implemented undefined_task fake_method; do
    ./target/release/submit-test-task --method $method --payload "test" 2>/dev/null || true
done

# Generic/unknown errors
echo "Generating unknown errors (5 tasks)..."
for i in {1..5}; do
    ./target/release/submit-test-task --method fail --payload "generic error $i" &
done
wait

# 4. Tasks with Dependencies
echo -e "\n=== Phase 4: Tasks with Dependencies ==="
echo "Creating dependency chains..."
# First create some base tasks
base1=$(./target/release/submit-test-task --method echo --payload "base task 1" 2>&1 | grep -o 'Task ID: [^ ]*' | cut -d' ' -f3)
base2=$(./target/release/submit-test-task --method echo --payload "base task 2" 2>&1 | grep -o 'Task ID: [^ ]*' | cut -d' ' -f3)

# Then create dependent tasks (using grpcurl for dependencies)
for i in {1..3}; do
    grpcurl -plaintext \
        -d "{
            \"task_id\": \"dependent-task-$i\",
            \"method\": \"echo\",
            \"payload\": \"$(echo -n "{\"data\": \"dependent task $i\"}" | base64)\",
            \"priority\": 5,
            \"dependencies\": [\"$base1\", \"$base2\"]
        }" \
        localhost:50051 task_scheduler.TaskScheduler/SubmitTask 2>/dev/null || true
done

# Also create tasks with non-existent dependencies (will fail)
for i in {1..3}; do
    grpcurl -plaintext \
        -d "{
            \"task_id\": \"failed-dependent-$i\",
            \"method\": \"echo\",
            \"payload\": \"$(echo -n "{\"data\": \"will fail $i\"}" | base64)\",
            \"priority\": 5,
            \"dependencies\": [\"non-existent-task-$i\"]
        }" \
        localhost:50051 task_scheduler.TaskScheduler/SubmitTask 2>/dev/null || true
done

# 5. Queue Depth Stress Test
echo -e "\n=== Phase 5: Queue Depth Stress Test ==="
echo "Submitting 100 tasks rapidly to demonstrate queue depth..."
for i in {1..100}; do
    ./target/release/submit-test-task --method sleep --payload "1000" &
    if [ $((i % 20)) -eq 0 ]; then
        wait
        echo "Submitted $i tasks..."
    fi
done
wait

echo ""
echo "Current queue depth:"
curl -s http://localhost:9000/metrics | grep "queue_depth" | grep -v "^#"

# 6. Storage Operations
echo -e "\n=== Phase 6: Storage Operations ==="
echo "Querying task results to trigger storage reads..."
for i in {1..20}; do
    # Try to get results for random task IDs
    grpcurl -plaintext \
        -d "{\"task_id\": \"$(uuidgen)\"}" \
        localhost:50051 task_scheduler.TaskScheduler/GetResult 2>/dev/null || true
done

# 7. Wait for Retry Cycles
echo -e "\n=== Phase 7: Retry Mechanism Testing ==="
echo "Waiting for failed tasks to go through retry cycles..."
echo "- Initial processing: 5 seconds"
wait_with_progress 5 "Waiting"
echo "- First retry cycle: 5 seconds"
wait_with_progress 5 "Waiting"
echo "- Second retry cycle: 10 seconds"
wait_with_progress 10 "Waiting"
echo "- Third retry cycle: 15 seconds"
wait_with_progress 15 "Waiting"
echo "- Final status updates: 5 seconds"
wait_with_progress 5 "Waiting"

# 8. Mixed Realistic Workload
echo -e "\n=== Phase 8: Mixed Realistic Workload ==="
echo "Submitting mixed tasks to simulate real-world usage..."
for i in {1..50}; do
    case $((i % 10)) in
        0) ./target/release/submit-test-task --method echo --payload "mixed-echo-$i" & ;;
        1) ./target/release/submit-test-task --method sleep --payload "500" & ;;
        2) ./target/release/submit-test-task --method init --payload "config-$i" & ;;
        3) ./target/release/submit-test-task --method uppercase --payload "text-$i" & ;;
        4) ./target/release/submit-test-task --method fail --payload "timeout" & ;;
        5) ./target/release/submit-test-task --method fail --payload "network" & ;;
        6) ./target/release/submit-test-task --method add --payload "2,3" & ;;
        7) ./target/release/submit-test-task --method multiply --payload "5,4" & ;;
        8) ./target/release/submit-test-task --method concat --payload "hello, ,world" & ;;
        9) ./target/release/submit-test-task --method lowercase --payload "MIXED-$i" & ;;
    esac
    
    if [ $((i % 10)) -eq 0 ]; then
        wait
        sleep 0.2
    fi
done
wait

# 9. Final Metrics Summary
echo -e "\n=== Final Metrics Summary ==="
echo ""
echo "1. Task Submission Totals by Method:"
curl -s http://localhost:9000/metrics | grep "task_manager_tasks_submitted_total" | grep -v "#" | sort

echo -e "\n2. Task Status Distribution:"
curl -s http://localhost:9000/metrics | grep "task_manager_tasks_status_total" | grep -v "#" | sort

echo -e "\n3. Error Categories (should show 10+ types):"
curl -s http://localhost:9000/metrics | grep "task_manager_tasks_errors_by_category_total" | grep -v "#" | grep -v "=0" | sort

echo -e "\n4. Task Execution Duration (sample):"
curl -s http://localhost:9000/metrics | grep "task_manager_task_execution_duration_seconds_count" | grep -v bucket | head -10

echo -e "\n5. Worker Metrics:"
curl -s http://localhost:9000/metrics | grep -E "task_manager_worker_pool_size|task_manager_worker_active_tasks" | grep -v "#"

echo -e "\n6. Queue Depth:"
curl -s http://localhost:9000/metrics | grep "task_manager_queue_depth" | grep -v "#"

echo -e "\n7. Storage Operations:"
curl -s http://localhost:9000/metrics | grep "task_manager_storage_operations_total" | grep -v "#" | head -5

echo -e "\n8. Task Retries:"
curl -s http://localhost:9000/metrics | grep "task_manager_task_retries_total" | grep -v "#" | head -5

echo -e "\n9. Unsupported Method Tasks:"
curl -s http://localhost:9000/metrics | grep "task_manager_unsupported_method_tasks_total" | grep -v "#"

echo -e "\n=== UltraThink Data Generation Complete ==="
echo ""
echo "Key Insights:"
echo "- Worker Pool Utilization: Should show activity during sleep task execution"
echo "- Average Processing Time: Sleep tasks generate non-zero execution times"
echo "- Queue Depth: Demonstrates positive values during bulk submissions"
echo "- Error Categories: Supporting 10+ types including custom errors"
echo "- Dependencies: Both successful and failed dependency chains"
echo ""
echo "Dashboard URLs:"
echo "- Operations Overview: http://localhost:3000/d/1-operations-overview"
echo "- Performance Analysis: http://localhost:3000/d/2-performance-analysis"
echo "- Business Metrics: http://localhost:3000/d/3-business-metrics"
echo ""
echo "Legacy Dashboard URLs (if still configured):"
echo "- Main Dashboard: http://localhost:3000/d/task-scheduler/task-scheduler-dashboard"
echo "- Advanced Dashboard: http://localhost:3000/d/task-scheduler-advanced/task-scheduler-advanced-dashboard"
echo ""
echo "Tips:"
echo "1. Refresh browser if data doesn't appear immediately"
echo "2. Adjust time range to 'Last 5 minutes' or 'Last 15 minutes'"
echo "3. Error metrics require tasks to fail after retries (~35 seconds)"
echo "4. Worker utilization is best viewed during sleep task execution"