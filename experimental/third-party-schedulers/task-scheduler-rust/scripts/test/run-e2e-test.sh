#!/bin/bash
# End-to-End Test Runner
# This script runs a complete end-to-end test of the task scheduler system

set -e

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Configuration
POSTGRES_URL="${TEST_POSTGRES_URL:-postgres://postgres:postgres@localhost:5432/task_scheduler_test}"
NATS_URL="${TEST_NATS_URL:-nats://localhost:4222}"
TEST_TIMEOUT=300 # 5 minutes

echo -e "${GREEN}Starting End-to-End Test${NC}"

# Function to cleanup on exit
cleanup() {
    echo -e "${YELLOW}Cleaning up test environment...${NC}"
    
    # Kill any running processes
    if [ ! -z "$MANAGER_PID" ]; then
        kill $MANAGER_PID 2>/dev/null || true
    fi
    if [ ! -z "$WORKER_PID" ]; then
        kill $WORKER_PID 2>/dev/null || true
    fi
    
    # Clean up test database
    psql "$POSTGRES_URL" -c "DROP TABLE IF EXISTS task_results CASCADE;" 2>/dev/null || true
    psql "$POSTGRES_URL" -c "DROP TABLE IF EXISTS task_history CASCADE;" 2>/dev/null || true
}

trap cleanup EXIT

# Step 1: Build all components
echo -e "${YELLOW}Building components...${NC}"
cargo build --release -p task-manager
cargo build --release -p task-worker
cargo build --release -p task-scheduler

# Step 2: Start task manager
echo -e "${YELLOW}Starting task manager...${NC}"
cat > /tmp/task-manager-config.toml << EOF
[server]
grpc_port = 50051
metrics_port = 9090

[storage]
type = "postgres"
url = "$POSTGRES_URL"
max_connections = 10
run_migrations = true

[queue]
nats_url = "$NATS_URL"
stream_name = "TASKS"
subject_prefix = "tasks"

[scheduler]
default_queue = "default"
max_retries = 3
retry_backoff_seconds = 60
EOF

./target/release/task-manager --config /tmp/task-manager-config.toml &
MANAGER_PID=$!

# Wait for manager to start
sleep 5

# Step 3: Start task worker
echo -e "${YELLOW}Starting task worker...${NC}"
cat > /tmp/task-worker-config.toml << EOF
[worker]
id = "test-worker-1"
queues = ["default", "priority"]
max_concurrent_tasks = 10

[queue]
nats_url = "$NATS_URL"
stream_name = "TASKS"
subject_prefix = "tasks"

[storage]
type = "postgres"
url = "$POSTGRES_URL"

[plugins]
enabled = true
plugin_dir = "./examples/plugins"
EOF

./target/release/task-worker --config /tmp/task-worker-config.toml &
WORKER_PID=$!

# Wait for worker to start
sleep 5

# Step 4: Run test scenarios
echo -e "${YELLOW}Running test scenarios...${NC}"

# Create a test client script
cat > /tmp/e2e-test-client.py << 'EOF'
#!/usr/bin/env python3
import grpc
import json
import time
import uuid
import sys
from concurrent.futures import ThreadPoolExecutor

# Import generated protobuf (this would normally be generated)
# For now, we'll use grpcurl for testing

def run_test(test_name, command):
    print(f"Running: {test_name}")
    result = os.system(command)
    if result == 0:
        print(f"✓ {test_name}: PASSED")
        return True
    else:
        print(f"✗ {test_name}: FAILED")
        return False

import os

# Test 1: Submit a simple task
task1_id = str(uuid.uuid4())
test1_cmd = f'''
grpcurl -plaintext -d '{{"task_id": "{task1_id}", "method": "example.echo", "args": [{{"type_url": "type.googleapis.com/google.protobuf.StringValue", "value": "CgVoZWxsbw=="}}], "is_async": true}}' localhost:50051 taskscheduler.TaskScheduler/SubmitTask
'''
run_test("Submit simple task", test1_cmd)

# Wait for task to complete
time.sleep(2)

# Test 2: Check task result
test2_cmd = f'''
grpcurl -plaintext -d '{{"task_id": "{task1_id}"}}' localhost:50051 taskscheduler.TaskScheduler/GetResult
'''
run_test("Get task result", test2_cmd)

# Test 3: Submit task with dependencies
task2_id = str(uuid.uuid4())
task3_id = str(uuid.uuid4())
test3_cmd = f'''
grpcurl -plaintext -d '{{"task_id": "{task2_id}", "method": "example.process", "args": [], "deps": [], "is_async": true}}' localhost:50051 taskscheduler.TaskScheduler/SubmitTask
'''
run_test("Submit dependency task", test3_cmd)

test4_cmd = f'''
grpcurl -plaintext -d '{{"task_id": "{task3_id}", "method": "example.finalize", "args": [], "deps": ["{task2_id}"], "is_async": true}}' localhost:50051 taskscheduler.TaskScheduler/SubmitTask
'''
run_test("Submit dependent task", test4_cmd)

# Wait for tasks to complete
time.sleep(5)

# Test 4: Cancel a running task
task4_id = str(uuid.uuid4())
test5_cmd = f'''
grpcurl -plaintext -d '{{"task_id": "{task4_id}", "method": "example.long_running", "args": [], "is_async": true}}' localhost:50051 taskscheduler.TaskScheduler/SubmitTask
'''
run_test("Submit long running task", test5_cmd)

time.sleep(1)

test6_cmd = f'''
grpcurl -plaintext -d '{{"task_id": "{task4_id}", "reason": "test cancellation"}}' localhost:50051 taskscheduler.TaskScheduler/CancelTask
'''
run_test("Cancel task", test6_cmd)

# Test 5: Concurrent task submission
print("Testing concurrent submissions...")
with ThreadPoolExecutor(max_workers=10) as executor:
    futures = []
    for i in range(20):
        task_id = str(uuid.uuid4())
        cmd = f'''grpcurl -plaintext -d '{{"task_id": "{task_id}", "method": "example.concurrent_test", "args": [{{"type_url": "type.googleapis.com/google.protobuf.Int32Value", "value": "CAE="}}], "is_async": true}}' localhost:50051 taskscheduler.TaskScheduler/SubmitTask'''
        futures.append(executor.submit(os.system, cmd))
    
    # Wait for all submissions
    for future in futures:
        future.result()

print("All concurrent tasks submitted")

# Wait for completion
time.sleep(10)

# Check metrics
print("Checking metrics endpoint...")
os.system("curl -s http://localhost:9090/metrics | grep -E '(task_|worker_)' | head -20")

print("\nEnd-to-end test completed!")
EOF

python3 /tmp/e2e-test-client.py

# Step 5: Verify results
echo -e "${YELLOW}Verifying test results...${NC}"

# Check if tasks were processed
TASK_COUNT=$(psql "$POSTGRES_URL" -t -c "SELECT COUNT(*) FROM task_results;" 2>/dev/null || echo "0")
if [ "$TASK_COUNT" -gt "20" ]; then
    echo -e "${GREEN}✓ Tasks were successfully processed (count: $TASK_COUNT)${NC}"
else
    echo -e "${RED}✗ Expected more than 20 tasks, found: $TASK_COUNT${NC}"
    exit 1
fi

# Check if worker processed tasks
WORKER_TASKS=$(psql "$POSTGRES_URL" -t -c "SELECT COUNT(*) FROM task_results WHERE worker_id = 'test-worker-1';" 2>/dev/null || echo "0")
if [ "$WORKER_TASKS" -gt "0" ]; then
    echo -e "${GREEN}✓ Worker successfully processed tasks (count: $WORKER_TASKS)${NC}"
else
    echo -e "${RED}✗ Worker did not process any tasks${NC}"
    exit 1
fi

echo -e "${GREEN}End-to-End test completed successfully!${NC}"