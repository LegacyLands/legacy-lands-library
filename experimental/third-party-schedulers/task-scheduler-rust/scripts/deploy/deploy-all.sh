#!/bin/bash
# Deploy all components of the task scheduler
# 
# Required components:
# - NATS with JetStream (message queue)
# - PostgreSQL (persistent storage)
# - Redis (caching layer)
# - Task Manager and Workers

set -e

# Source common utilities
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../common.sh"

# Configuration
NAMESPACE=${NAMESPACE:-$DEFAULT_NAMESPACE}

print_color "$BLUE" "🚀 Deploying Rust Task Scheduler"
print_color "$BLUE" "================================"

# Create namespace if it doesn't exist
echo -e "\n1️⃣ Creating namespace..."
kubectl create namespace $NAMESPACE --dry-run=client -o yaml | kubectl apply -f -

# Deploy CRDs
echo -e "\n2️⃣ Deploying CRDs..."
kubectl apply -f "$PROJECT_ROOT/deploy/k8s/crds/"

# Deploy RBAC
echo -e "\n3️⃣ Deploying RBAC..."
kubectl apply -f "$PROJECT_ROOT/deploy/k8s/rbac/"

# Deploy NATS
echo -e "\n4️⃣ Deploying NATS with JetStream..."
kubectl apply -f "$PROJECT_ROOT/deploy/k8s/nats/"

# Wait for NATS
echo "Waiting for NATS to be ready..."
kubectl wait --for=condition=ready pod -l app=nats -n $NAMESPACE --timeout=120s

# Deploy PostgreSQL (required)
echo -e "\n5️⃣ Deploying PostgreSQL storage..."
kubectl apply -f "$PROJECT_ROOT/deploy/k8s/storage/postgres.yaml"
echo "Waiting for PostgreSQL to be ready..."
kubectl wait --for=condition=ready pod -l app=postgres -n $NAMESPACE --timeout=120s

# Deploy Redis (required)
echo -e "\n6️⃣ Deploying Redis cache..."
kubectl apply -f "$PROJECT_ROOT/deploy/k8s/infrastructure/redis.yaml"
echo "Waiting for Redis to be ready..."
kubectl wait --for=condition=ready pod -l app=redis -n $NAMESPACE --timeout=120s

# Deploy services
echo -e "\n7️⃣ Deploying services..."
kubectl apply -f "$PROJECT_ROOT/deploy/k8s/services/"

# Wait for deployments
echo -e "\n8️⃣ Waiting for deployments to be ready..."
kubectl wait --for=condition=available deployment --all -n $NAMESPACE --timeout=300s

# Show status
echo -e "\n9️⃣ Deployment Status:"
kubectl get all -n $NAMESPACE

echo -e "\n✅ Deployment complete!"
echo ""
echo "📋 Next steps:"
echo "  1. Check system status: ./scripts/system-summary.sh"
echo "  2. Run tests: ./scripts/run-k8s-tests.sh"
echo "  3. Submit a test task: kubectl apply -f examples/task-simple.yaml"
echo ""
echo "🔗 Access points:"
echo "  • gRPC API: kubectl port-forward svc/task-manager 50051:50051 -n $NAMESPACE"
echo "  • Metrics: kubectl port-forward svc/task-manager 9000:9000 -n $NAMESPACE"