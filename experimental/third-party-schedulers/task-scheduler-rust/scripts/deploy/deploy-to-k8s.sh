#!/bin/bash
# Deploy Task Scheduler to Kubernetes
# Usage: ./deploy-to-k8s.sh [environment]

set -e

# Configuration
ENVIRONMENT="${1:-test}"
NAMESPACE="task-scheduler-$ENVIRONMENT"
REGISTRY="${DOCKER_REGISTRY:-localhost:5000}"
VERSION="${VERSION:-latest}"

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "${GREEN}Deploying Task Scheduler to Kubernetes${NC}"
echo "Environment: $ENVIRONMENT"
echo "Namespace: $NAMESPACE"
echo "Registry: $REGISTRY"

# Step 1: Build Docker images
echo -e "${YELLOW}Building Docker images...${NC}"

# Build task-manager image
docker build -f deploy/docker/Dockerfile.manager -t $REGISTRY/task-manager:$VERSION .
docker build -f deploy/docker/Dockerfile.worker -t $REGISTRY/task-worker:$VERSION .
docker build -f deploy/docker/Dockerfile.operator -t $REGISTRY/task-operator:$VERSION .

# Push images if not using local registry
if [ "$REGISTRY" != "localhost:5000" ]; then
    echo -e "${YELLOW}Pushing images to registry...${NC}"
    docker push $REGISTRY/task-manager:$VERSION
    docker push $REGISTRY/task-worker:$VERSION
    docker push $REGISTRY/task-operator:$VERSION
fi

# Step 2: Create namespace
echo -e "${YELLOW}Creating namespace...${NC}"
kubectl create namespace $NAMESPACE --dry-run=client -o yaml | kubectl apply -f -

# Step 3: Deploy infrastructure components
echo -e "${YELLOW}Deploying infrastructure...${NC}"

# Deploy PostgreSQL
cat <<EOF | kubectl apply -n $NAMESPACE -f -
apiVersion: v1
kind: ConfigMap
metadata:
  name: postgres-config
data:
  POSTGRES_DB: task_scheduler
  POSTGRES_USER: postgres
  POSTGRES_PASSWORD: postgres
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: postgres-pvc
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 10Gi
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: postgres
spec:
  replicas: 1
  selector:
    matchLabels:
      app: postgres
  template:
    metadata:
      labels:
        app: postgres
    spec:
      containers:
      - name: postgres
        image: postgres:15
        envFrom:
        - configMapRef:
            name: postgres-config
        ports:
        - containerPort: 5432
        volumeMounts:
        - name: postgres-storage
          mountPath: /var/lib/postgresql/data
      volumes:
      - name: postgres-storage
        persistentVolumeClaim:
          claimName: postgres-pvc
---
apiVersion: v1
kind: Service
metadata:
  name: postgres
spec:
  selector:
    app: postgres
  ports:
  - port: 5432
    targetPort: 5432
EOF

# Deploy NATS
cat <<EOF | kubectl apply -n $NAMESPACE -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: nats
spec:
  replicas: 1
  selector:
    matchLabels:
      app: nats
  template:
    metadata:
      labels:
        app: nats
    spec:
      containers:
      - name: nats
        image: nats:2.10-alpine
        args: ["-js", "-m", "8222"]
        ports:
        - containerPort: 4222
          name: client
        - containerPort: 8222
          name: monitoring
---
apiVersion: v1
kind: Service
metadata:
  name: nats
spec:
  selector:
    app: nats
  ports:
  - name: client
    port: 4222
    targetPort: 4222
  - name: monitoring
    port: 8222
    targetPort: 8222
EOF

# Wait for infrastructure to be ready
echo -e "${YELLOW}Waiting for infrastructure to be ready...${NC}"
kubectl wait --for=condition=available --timeout=300s deployment/postgres -n $NAMESPACE
kubectl wait --for=condition=available --timeout=300s deployment/nats -n $NAMESPACE

# Step 4: Deploy CRDs
echo -e "${YELLOW}Deploying CRDs...${NC}"
kubectl apply -f deploy/kubernetes/crds/

# Step 5: Deploy task scheduler components
echo -e "${YELLOW}Deploying task scheduler components...${NC}"

# Deploy task-manager
cat <<EOF | kubectl apply -n $NAMESPACE -f -
apiVersion: v1
kind: ConfigMap
metadata:
  name: task-manager-config
data:
  config.toml: |
    [server]
    grpc_port = 50051
    metrics_port = 9090
    
    [storage]
    type = "postgres"
    url = "postgres://postgres:postgres@postgres:5432/task_scheduler"
    max_connections = 20
    run_migrations = true
    
    [queue]
    nats_url = "nats://nats:4222"
    stream_name = "TASKS"
    subject_prefix = "tasks"
    
    [scheduler]
    default_queue = "default"
    max_retries = 3
    retry_backoff_seconds = 60
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: task-manager
spec:
  replicas: 2
  selector:
    matchLabels:
      app: task-manager
  template:
    metadata:
      labels:
        app: task-manager
    spec:
      containers:
      - name: task-manager
        image: $REGISTRY/task-manager:$VERSION
        ports:
        - containerPort: 50051
          name: grpc
        - containerPort: 9090
          name: metrics
        volumeMounts:
        - name: config
          mountPath: /etc/task-manager
        env:
        - name: CONFIG_PATH
          value: /etc/task-manager/config.toml
        livenessProbe:
          httpGet:
            path: /health
            port: 9090
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /ready
            port: 9090
          initialDelaySeconds: 10
          periodSeconds: 5
      volumes:
      - name: config
        configMap:
          name: task-manager-config
---
apiVersion: v1
kind: Service
metadata:
  name: task-manager
spec:
  selector:
    app: task-manager
  ports:
  - name: grpc
    port: 50051
    targetPort: 50051
  - name: metrics
    port: 9090
    targetPort: 9090
EOF

# Deploy task-worker
cat <<EOF | kubectl apply -n $NAMESPACE -f -
apiVersion: v1
kind: ConfigMap
metadata:
  name: task-worker-config
data:
  config.toml: |
    [worker]
    queues = ["default", "priority"]
    max_concurrent_tasks = 20
    
    [queue]
    nats_url = "nats://nats:4222"
    stream_name = "TASKS"
    subject_prefix = "tasks"
    
    [storage]
    type = "postgres"
    url = "postgres://postgres:postgres@postgres:5432/task_scheduler"
    
    [plugins]
    enabled = true
    plugin_dir = "/plugins"
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: task-worker
spec:
  replicas: 3
  selector:
    matchLabels:
      app: task-worker
  template:
    metadata:
      labels:
        app: task-worker
    spec:
      containers:
      - name: task-worker
        image: $REGISTRY/task-worker:$VERSION
        volumeMounts:
        - name: config
          mountPath: /etc/task-worker
        - name: plugins
          mountPath: /plugins
        env:
        - name: CONFIG_PATH
          value: /etc/task-worker/config.toml
        - name: WORKER_ID
          valueFrom:
            fieldRef:
              fieldPath: metadata.name
        resources:
          requests:
            cpu: 500m
            memory: 512Mi
          limits:
            cpu: 2000m
            memory: 2Gi
      volumes:
      - name: config
        configMap:
          name: task-worker-config
      - name: plugins
        emptyDir: {}
EOF

# Deploy task-operator
cat <<EOF | kubectl apply -n $NAMESPACE -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: task-operator
spec:
  replicas: 1
  selector:
    matchLabels:
      app: task-operator
  template:
    metadata:
      labels:
        app: task-operator
    spec:
      serviceAccountName: task-operator
      containers:
      - name: task-operator
        image: $REGISTRY/task-operator:$VERSION
        env:
        - name: OPERATOR_NAMESPACE
          valueFrom:
            fieldRef:
              fieldPath: metadata.namespace
        - name: TASK_MANAGER_ENDPOINT
          value: "task-manager:50051"
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: task-operator
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: task-operator
rules:
- apiGroups: ["taskscheduler.io"]
  resources: ["tasks", "taskresults"]
  verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
- apiGroups: [""]
  resources: ["pods", "services", "configmaps"]
  verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
- apiGroups: ["batch"]
  resources: ["jobs"]
  verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: task-operator
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: task-operator
subjects:
- kind: ServiceAccount
  name: task-operator
  namespace: $NAMESPACE
EOF

# Wait for deployments to be ready
echo -e "${YELLOW}Waiting for deployments to be ready...${NC}"
kubectl wait --for=condition=available --timeout=300s deployment/task-manager -n $NAMESPACE
kubectl wait --for=condition=available --timeout=300s deployment/task-worker -n $NAMESPACE
kubectl wait --for=condition=available --timeout=300s deployment/task-operator -n $NAMESPACE

# Step 6: Create test resources
if [ "$ENVIRONMENT" = "test" ]; then
    echo -e "${YELLOW}Creating test resources...${NC}"
    
    cat <<EOF | kubectl apply -n $NAMESPACE -f -
apiVersion: taskscheduler.io/v1
kind: Task
metadata:
  name: test-task-1
spec:
  method: example.echo
  args:
  - value: "Hello from Kubernetes!"
  priority: 5
  retryConfig:
    maxRetries: 3
    backoffStrategy: exponential
---
apiVersion: taskscheduler.io/v1
kind: Task
metadata:
  name: test-task-2
spec:
  method: example.process
  dependencies:
  - test-task-1
  priority: 3
EOF
fi

# Step 7: Setup monitoring (optional)
if [ "$DEPLOY_MONITORING" = "true" ]; then
    echo -e "${YELLOW}Setting up monitoring...${NC}"
    
    # Deploy Prometheus
    kubectl apply -n $NAMESPACE -f - <<EOF
apiVersion: v1
kind: ConfigMap
metadata:
  name: prometheus-config
data:
  prometheus.yml: |
    global:
      scrape_interval: 15s
    scrape_configs:
    - job_name: 'task-manager'
      kubernetes_sd_configs:
      - role: pod
        namespaces:
          names:
          - $NAMESPACE
      relabel_configs:
      - source_labels: [__meta_kubernetes_pod_label_app]
        action: keep
        regex: task-manager
      - source_labels: [__address__]
        action: replace
        regex: ([^:]+)(?::\d+)?
        replacement: \$1:9090
        target_label: __address__
EOF
fi

# Final status
echo -e "${GREEN}Deployment completed successfully!${NC}"
echo
echo "To access the services:"
echo "  Task Manager gRPC: kubectl port-forward -n $NAMESPACE svc/task-manager 50051:50051"
echo "  Task Manager Metrics: kubectl port-forward -n $NAMESPACE svc/task-manager 9090:9090"
echo
echo "To view logs:"
echo "  kubectl logs -n $NAMESPACE -l app=task-manager"
echo "  kubectl logs -n $NAMESPACE -l app=task-worker"
echo "  kubectl logs -n $NAMESPACE -l app=task-operator"
echo
echo "To submit a task:"
echo "  kubectl apply -n $NAMESPACE -f examples/k8s/sample-task.yaml"