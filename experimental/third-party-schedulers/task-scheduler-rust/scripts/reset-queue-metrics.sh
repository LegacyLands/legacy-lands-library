#!/bin/bash

# Script to reset queue and clean up stuck tasks

echo "=== Queue and Metrics Reset Script ==="
echo "Date: $(date)"
echo ""

cd "$(dirname "$0")/.."

echo "WARNING: This will reset the task queue and metrics!"
echo "Press Ctrl+C to cancel, or wait 5 seconds to continue..."
sleep 5

echo -e "\n=== Current Status ==="
echo "Queue depth:"
curl -s http://localhost:9000/metrics | grep "task_manager_queue_depth" | grep -v "#"
echo "Task status:"
curl -s http://localhost:9000/metrics | grep "task_manager_tasks_status_total" | grep -v "#" | tail -5

echo -e "\n=== Stopping Services ==="
cd deploy/docker
docker compose stop task-worker task-manager

echo -e "\n=== Restarting NATS to clear queues ==="
docker compose restart nats

echo -e "\n=== Starting Services ==="
docker compose start task-manager
sleep 5
docker compose start task-worker

echo -e "\n=== Waiting for services to be ready ==="
sleep 10

echo -e "\n=== New Status ==="
echo "Queue depth:"
curl -s http://localhost:9000/metrics | grep "task_manager_queue_depth" | grep -v "#"
echo "Task status:"
curl -s http://localhost:9000/metrics | grep "task_manager_tasks_status_total" | grep -v "#" | tail -5

echo -e "\n=== Reset Complete ==="
echo "The queue has been cleared and metrics reset."
echo "You can now run ./scripts/generate-all-metrics-data.sh to generate fresh data."