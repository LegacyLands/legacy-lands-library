#!/bin/bash
# Generate correct Prometheus configuration for Docker Compose deployment
# 为 Docker Compose 部署生成正确的 Prometheus 配置

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$(dirname "$SCRIPT_DIR")")"

# Number of workers (from docker-compose.yaml)
WORKER_COUNT=50

echo "Generating Prometheus configuration for $WORKER_COUNT workers..."

# Generate prometheus.yml
cat > "$PROJECT_ROOT/deploy/docker/prometheus.yml" << EOF
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  # Task Manager metrics
  - job_name: 'task-manager'
    static_configs:
      - targets: ['task-scheduler-manager:9000']
        labels:
          service: 'task-manager'
          
  # Task Worker metrics (all $WORKER_COUNT workers)
  - job_name: 'task-worker'
    static_configs:
      - targets:
EOF

# Generate worker targets
for i in $(seq 1 $WORKER_COUNT); do
    echo "          - 'docker-task-worker-$i:9001'" >> "$PROJECT_ROOT/deploy/docker/prometheus.yml"
done

# Complete the configuration
cat >> "$PROJECT_ROOT/deploy/docker/prometheus.yml" << EOF
        labels:
          service: 'task-worker'
          
  # NATS metrics
  - job_name: 'nats'
    static_configs:
      - targets: ['task-scheduler-nats:8222']
        labels:
          service: 'nats'
    metrics_path: '/varz'
    
  # Redis metrics (if enabled)
  - job_name: 'redis'
    static_configs:
      - targets: ['task-scheduler-redis:6379']
        labels:
          service: 'redis'
    metrics_path: '/metrics'
    
  # PostgreSQL metrics (if exporter enabled)
  - job_name: 'postgres'
    static_configs:
      - targets: ['task-scheduler-postgres:5432']
        labels:
          service: 'postgres'
    metrics_path: '/metrics'
EOF

echo "✅ Prometheus configuration generated successfully!"
echo "📍 Location: $PROJECT_ROOT/deploy/docker/prometheus.yml"
echo "🎯 Configured for $WORKER_COUNT workers"