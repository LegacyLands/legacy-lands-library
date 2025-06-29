#!/bin/bash
# Restart workers to reset consumer state

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

echo "=== Restarting Workers ==="
echo ""

# Step 1: Stop workers
echo "Step 1: Stopping all workers..."
docker compose -f deploy/docker/docker-compose.yaml stop task-worker

# Step 2: Start workers
echo -e "\nStep 2: Starting workers..."
docker compose -f deploy/docker/docker-compose.yaml up -d task-worker

# Step 3: Wait for workers to be ready
echo -e "\nWaiting for workers to be ready..."
sleep 10

# Step 4: Check worker metrics
echo -e "\nStep 4: Checking worker metrics..."
echo "Worker pool size:"
curl -s http://localhost:9000/metrics | grep -E "task_manager_worker_pool_size" | grep -v "# "

echo -e "\nWorker active tasks:"
curl -s http://localhost:9000/metrics | grep -E "task_manager_worker_active_tasks" | grep -v "# " | head -10

echo -e "\nQueue depth:"
curl -s http://localhost:9000/metrics | grep -E "task_manager_queue_depth" | grep -v "# "

echo -e "\n=== Workers Restarted ==="#