#!/bin/bash

# Debug script to test task execution

set -e

echo "Starting services..."
docker compose -f docker-compose.test.yml up -d

echo "Waiting for services to be ready..."
sleep 10

echo "Building binaries..."
cargo build --bin task-manager --bin task-worker

echo "Starting task-manager..."
RUST_LOG=debug \
GRPC_ADDRESS=0.0.0.0:50051 \
METRICS_ADDRESS=0.0.0.0:9091 \
NATS_URL=nats://localhost:4222 \
./target/debug/task-manager &
MANAGER_PID=$!

sleep 5

echo "Starting task-worker..."
RUST_LOG=debug \
MANAGER_ADDRESS=localhost:50051 \
NATS_URL=nats://localhost:4222 \
WORKER_ID=test-worker-1 \
./target/debug/task-worker &
WORKER_PID=$!

sleep 5

# Keep running for debugging
echo "Services are running. Press Ctrl+C to stop."
echo "Manager PID: $MANAGER_PID"
echo "Worker PID: $WORKER_PID"
echo ""
echo "You can test with:"
echo "  grpcurl -plaintext -d '{\"task_id\":\"test-1\",\"method\":\"echo\",\"args\":[{\"type_url\":\"type.googleapis.com/google.protobuf.StringValue\",\"value\":\"\\\"hello\\\"\"}],\"is_async\":false}' localhost:50051 taskscheduler.TaskScheduler/SubmitTask"

# Wait for interrupt
trap "kill $MANAGER_PID $WORKER_PID; docker compose -f docker-compose.test.yml down" EXIT
wait