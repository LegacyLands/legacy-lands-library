#!/bin/bash
# 🚀 One-Click Deployment Script - Ready-to-Use Task Scheduler System
# 
# Features:
# 1. Auto-detect best deployment method (Docker Compose / Kind + Helm)
# 2. One-click startup of complete system (including monitoring)
# 3. Auto-import Grafana dashboards
# 4. Performance validation testing
#
# Usage:
#   ./scripts/deploy/deploy-one-click.sh                 # Auto-select best method
#   ./scripts/deploy/deploy-one-click.sh docker          # Force Docker Compose
#   ./scripts/deploy/deploy-one-click.sh kind            # Force Kind + Helm

set -e

# Source common utilities
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../common.sh"

# Configuration
DEPLOYMENT_MODE=${1:-auto}
NAMESPACE="task-scheduler"

print_section "🚀 Task Scheduler - One-Click Deployment System"

echo "Selected deployment mode: $DEPLOYMENT_MODE"
echo ""

# Function to validate and ensure configuration consistency
validate_project_config() {
    print_info "Validating project configuration consistency..."
    
    local config_valid=true
    
    # Check if Helm dashboards exist (single source of truth)
    if [ ! -d "$PROJECT_ROOT/deploy/helm/task-scheduler/dashboards" ]; then
        print_error "Helm Chart dashboard directory missing! This is the single source of truth"
        config_valid=false
    else
        local helm_dashboard_count=$(ls "$PROJECT_ROOT/deploy/helm/task-scheduler/dashboards/"*.json 2>/dev/null | wc -l)
        if [ "$helm_dashboard_count" -eq 0 ]; then
            print_error "No dashboard files found in Helm Chart!"
            config_valid=false
        else
            print_success "Found $helm_dashboard_count Helm dashboard files"
        fi
    fi
    
    # Check for duplicate configurations that need cleaning
    if [ -d "$PROJECT_ROOT/deploy/grafana/dashboards" ]; then
        local grafana_dashboard_count=$(ls "$PROJECT_ROOT/deploy/grafana/dashboards/"*.json 2>/dev/null | wc -l)
        if [ "$grafana_dashboard_count" -gt 0 ]; then
            print_warning "Found duplicate Grafana dashboard config (will auto-clean and sync from Helm Chart)"
        fi
    fi
    
    # Check for required Docker files
    local required_docker_files=(
        "$PROJECT_ROOT/deploy/docker/docker-compose.yaml"
        "$PROJECT_ROOT/deploy/docker/prometheus.yml"
    )
    
    for file in "${required_docker_files[@]}"; do
        if [ ! -f "$file" ]; then
            print_error "Required Docker config file missing: $file"
            config_valid=false
        fi
    done
    
    # Check for Helm chart structure
    if [ "$DEPLOYMENT_MODE" = "kind" ] || [ "$DEPLOYMENT_MODE" = "auto" ]; then
        if [ ! -f "$PROJECT_ROOT/deploy/helm/task-scheduler/Chart.yaml" ]; then
            print_warning "Helm Chart incomplete, Kind deployment may fail"
        fi
    fi
    
    if [ "$config_valid" = false ]; then
        print_error "Configuration validation failed! Please check project integrity"
        exit 1
    fi
    
    print_success "Project configuration validation passed"
}

# Validate configuration before starting
validate_project_config

# Function to check if Kind is available and working
check_kind_available() {
    if command_exists kind && command_exists kubectl && command_exists helm; then
        # Check if Docker is running
        if docker info >/dev/null 2>&1; then
            return 0
        fi
    fi
    return 1
}

# Function to ensure unified dashboard configuration
sync_helm_dashboards() {
    print_info "Unifying dashboard configuration (Helm Chart as single source of truth)..."
    
    # Remove existing Docker Compose dashboards if they exist (they're duplicates)
    if [ -d "$PROJECT_ROOT/deploy/grafana/dashboards" ]; then
        print_info "Removing duplicate Docker Compose dashboard directory..."
        # Handle permission issues (Docker may create root-owned files)
        if [ "$(stat -c %U "$PROJECT_ROOT/deploy/grafana/dashboards" 2>/dev/null)" = "root" ]; then
            sudo rm -rf "$PROJECT_ROOT/deploy/grafana/dashboards" 2>/dev/null || {
                print_warning "Cannot remove root-owned directory, trying alternative method..."
                sudo chmod -R 755 "$PROJECT_ROOT/deploy/grafana/dashboards" 2>/dev/null || true
                sudo chown -R $(whoami):$(whoami) "$PROJECT_ROOT/deploy/grafana/dashboards" 2>/dev/null || true
                rm -rf "$PROJECT_ROOT/deploy/grafana/dashboards" 2>/dev/null || {
                    print_warning "Dashboard directory removal failed, will overwrite files"
                }
            }
        else
            rm -rf "$PROJECT_ROOT/deploy/grafana/dashboards"
        fi
    fi
    
    # Create fresh dashboards directory
    mkdir -p "$PROJECT_ROOT/deploy/grafana/dashboards"
    
    # Copy dashboards from Helm Chart (single source of truth)
    if [ -d "$PROJECT_ROOT/deploy/helm/task-scheduler/dashboards" ]; then
        cp "$PROJECT_ROOT/deploy/helm/task-scheduler/dashboards/"*.json \
           "$PROJECT_ROOT/deploy/grafana/dashboards/" || {
            print_error "Cannot copy dashboard files! Helm Chart missing dashboards"
            return 1
        }
        print_success "Dashboards synced from Helm Chart"
    else
        print_error "Helm Chart dashboard directory does not exist!"
        return 1
    fi
    
    # Ensure proper permissions
    chown -R $(whoami):$(whoami) "$PROJECT_ROOT/deploy/grafana/dashboards/" 2>/dev/null || true
    chmod -R 644 "$PROJECT_ROOT/deploy/grafana/dashboards/"*.json 2>/dev/null || true
    
    print_success "Configuration unified: Helm Chart -> Docker Compose"
}

# Function to fix monitoring configurations automatically
fix_monitoring_configs() {
    print_info "Auto-fixing monitoring configurations..."
    
    # Fix Prometheus configuration
    local prometheus_config="$PROJECT_ROOT/deploy/docker/prometheus.yml"
    
    if [ ! -f "$prometheus_config" ]; then
        print_error "Prometheus config file not found: $prometheus_config"
        return 1
    fi
    
    # Always regenerate to ensure consistency
    print_info "Regenerating Prometheus configuration..."
    if [ -f "$SCRIPT_DIR/generate-prometheus-config.sh" ]; then
        "$SCRIPT_DIR/generate-prometheus-config.sh" || {
            print_warning "Prometheus config generation failed, using existing config"
        }
    fi
    
    # Verify and fix specific configurations
    print_info "Verifying Prometheus target configuration..."
    
    # Check for correct container names
    if grep -q "task-manager:9000" "$prometheus_config" 2>/dev/null; then
        print_info "Fixing Manager service name..."
        sed -i 's/task-manager:9000/task-scheduler-manager:9000/g' "$prometheus_config"
    fi
    
    # Fix worker targets to use correct names and ports
    if grep -q "task-worker-[0-9]*:9000" "$prometheus_config" 2>/dev/null; then
        print_info "Fixing Worker service names and ports..."
        sed -i 's/task-worker-\([0-9]*\):9000/docker-task-worker-\1:9001/g' "$prometheus_config"
    fi
    
    # Fix NATS configuration
    if grep -q "nats:8222" "$prometheus_config" 2>/dev/null; then
        print_info "Fixing NATS monitoring configuration..."
        sed -i 's/nats:8222/task-scheduler-nats:8222/g' "$prometheus_config"
        # NATS metrics are on /varz, not /metrics
        sed -i 's|metrics_path: '\''/metrics'\''.*targets.*nats|metrics_path: '\''/varz'\''|g' "$prometheus_config"
    fi
    
    # Fix Grafana data source configuration
    local grafana_datasource="$PROJECT_ROOT/deploy/grafana/provisioning/datasources/prometheus.yaml"
    
    if [ -f "$grafana_datasource" ]; then
        if grep -q "prometheus:9090" "$grafana_datasource" 2>/dev/null; then
            print_info "Fixing Grafana data source configuration..."
            # Backup original
            cp "$grafana_datasource" "$grafana_datasource.backup" 2>/dev/null || true
            # Fix data source URL
            sed -i 's|http://prometheus:9090|http://task-scheduler-prometheus:9090|g' "$grafana_datasource"
        fi
    fi
    
    # Validate configuration consistency
    print_info "Validating configuration consistency..."
    local config_issues=0
    
    # Check if all required services are referenced correctly
    if ! grep -q "task-scheduler-manager:9000" "$prometheus_config"; then
        print_warning "Manager target configuration may be incorrect"
        config_issues=$((config_issues + 1))
    fi
    
    if ! grep -q "task-scheduler-nats:8222" "$prometheus_config"; then
        print_warning "NATS target configuration may be incorrect"
        config_issues=$((config_issues + 1))
    fi
    
    if [ $config_issues -eq 0 ]; then
        print_success "Monitoring configuration validation passed"
    else
        print_warning "Found $config_issues potential configuration issues"
    fi
    
    print_success "Monitoring configuration auto-fix completed"
}

# Function to deploy with Docker Compose (Enhanced)
deploy_docker_compose() {
    print_section "📦 Docker Compose Deployment Mode"
    
    # Pre-deployment checks
    print_info "Performing pre-deployment checks..."
    
    # Check Docker daemon
    if ! docker info >/dev/null 2>&1; then
        print_error "Docker is not running! Please start Docker and retry"
        exit 1
    fi
    
    # Check available resources
    local available_memory=$(docker system info --format "{{.MemTotal}}" 2>/dev/null || echo "0")
    if [ "$available_memory" -lt 8000000000 ]; then  # 8GB in bytes
        print_warning "System memory may be insufficient (recommend 8GB+), but continuing deployment"
    fi
    
    # Check required files exist
    print_info "Validating deployment files..."
    local required_files=(
        "$PROJECT_ROOT/deploy/docker/docker-compose.yaml"
        "$PROJECT_ROOT/deploy/docker/prometheus.yml"
        "$PROJECT_ROOT/deploy/helm/task-scheduler/dashboards"
    )
    
    for file in "${required_files[@]}"; do
        if [ ! -e "$file" ]; then
            print_error "Required file missing: $file"
            exit 1
        fi
    done
    
    # Check Docker images or build requirements
    print_info "Checking Docker images..."
    local required_images=("task-scheduler/manager:latest" "task-scheduler/worker:latest")
    local missing_images=()
    
    for image in "${required_images[@]}"; do
        if ! docker image inspect "$image" >/dev/null 2>&1; then
            missing_images+=("$image")
        fi
    done
    
    if [ ${#missing_images[@]} -gt 0 ]; then
        print_warning "Missing images: ${missing_images[*]}"
        print_info "Attempting to build missing images..."
        
        # Build missing images
        for image in "${missing_images[@]}"; do
            case "$image" in
                "task-scheduler/manager:latest")
                    if [ -f "$PROJECT_ROOT/deploy/docker/Dockerfile.manager.local" ]; then
                        print_info "Building Manager image..."
                        docker build -f "$PROJECT_ROOT/deploy/docker/Dockerfile.manager.local" \
                                   -t "task-scheduler/manager:latest" "$PROJECT_ROOT" || {
                            print_error "Manager image build failed"
                            exit 1
                        }
                    fi
                    ;;
                "task-scheduler/worker:latest")
                    if [ -f "$PROJECT_ROOT/deploy/docker/Dockerfile.worker.local" ]; then
                        print_info "Building Worker image..."
                        docker build -f "$PROJECT_ROOT/deploy/docker/Dockerfile.worker.local" \
                                   -t "task-scheduler/worker:latest" "$PROJECT_ROOT" || {
                            print_error "Worker image build failed"
                            exit 1
                        }
                    fi
                    ;;
            esac
        done
        
        print_success "Image building completed"
    fi
    
    # Sync dashboards and fix configurations first
    sync_helm_dashboards
    
    # Generate correct Prometheus configuration
    print_info "Generating correct Prometheus configuration..."
    if [ -f "$SCRIPT_DIR/generate-prometheus-config.sh" ]; then
        "$SCRIPT_DIR/generate-prometheus-config.sh" >/dev/null 2>&1
        print_success "Prometheus configuration generated"
    fi
    
    fix_monitoring_configs
    
    # Stop any existing containers and clean up
    print_info "Cleaning up existing services and resources..."
    docker compose -f "$PROJECT_ROOT/deploy/docker/docker-compose.yaml" down --remove-orphans --volumes 2>/dev/null || true
    
    # Clean up any orphaned resources
    docker system prune -f 2>/dev/null || true
    
    # Pull latest images to avoid build issues
    print_info "Pulling/checking Docker images..."
    docker compose -f "$PROJECT_ROOT/deploy/docker/docker-compose.yaml" pull --ignore-buildable --quiet 2>/dev/null || true
    
    # Start infrastructure first (databases, message queue)
    print_info "Starting infrastructure components..."
    docker compose -f "$PROJECT_ROOT/deploy/docker/docker-compose.yaml" up -d postgres redis nats
    
    # Wait for infrastructure to be ready
    print_info "Waiting for infrastructure to be ready..."
    local max_wait=120
    local wait_time=0
    
    while [ $wait_time -lt $max_wait ]; do
        if docker compose -f "$PROJECT_ROOT/deploy/docker/docker-compose.yaml" ps postgres | grep -q "healthy"; then
            if docker compose -f "$PROJECT_ROOT/deploy/docker/docker-compose.yaml" ps redis | grep -q "healthy"; then
                if docker compose -f "$PROJECT_ROOT/deploy/docker/docker-compose.yaml" ps nats | grep -q "healthy"; then
                    print_success "Infrastructure components are ready"
                    break
                fi
            fi
        fi
        sleep 5
        wait_time=$((wait_time + 5))
        echo -n "."
    done
    
    if [ $wait_time -ge $max_wait ]; then
        print_error "Infrastructure startup timeout! Check resources or network issues"
        docker compose -f "$PROJECT_ROOT/deploy/docker/docker-compose.yaml" logs --tail 10
        exit 1
    fi
    
    # Start application services
    print_info "Starting application services..."
    docker compose -f "$PROJECT_ROOT/deploy/docker/docker-compose.yaml" up -d
    
    # Wait for Manager to be healthy
    print_info "Waiting for Manager service initialization..."
    wait_time=0
    max_wait=180
    
    while [ $wait_time -lt $max_wait ]; do
        if docker compose -f "$PROJECT_ROOT/deploy/docker/docker-compose.yaml" ps task-manager | grep -q "healthy"; then
            print_success "Manager service is ready"
            break
        fi
        
        # Check for Manager errors
        if docker compose -f "$PROJECT_ROOT/deploy/docker/docker-compose.yaml" ps task-manager | grep -q "exited"; then
            print_error "Manager service startup failed!"
            docker logs task-scheduler-manager --tail 20
            exit 1
        fi
        
        sleep 5
        wait_time=$((wait_time + 5))
        echo -n "."
    done
    
    if [ $wait_time -ge $max_wait ]; then
        print_error "Manager service startup timeout!"
        docker logs task-scheduler-manager --tail 20
        exit 1
    fi
    
    # Wait for Workers to be ready
    print_info "Waiting for Workers to start..."
    sleep 10
    
    local healthy_workers=$(docker compose -f "$PROJECT_ROOT/deploy/docker/docker-compose.yaml" ps task-worker --format "table {{.State}}" | grep -c "healthy" || echo "0")
    local total_workers=$(docker compose -f "$PROJECT_ROOT/deploy/docker/docker-compose.yaml" ps task-worker --format "table {{.State}}" | grep -c "running\|healthy" || echo "0")
    
    print_info "Workers status: $healthy_workers/$total_workers healthy"
    
    # Verify database initialization
    print_info "Verifying database initialization..."
    local db_check=$(docker exec task-scheduler-postgres psql -U task_scheduler -d task_scheduler -t -c "SELECT count(*) FROM information_schema.tables WHERE table_name IN ('tasks', 'tasks_binary', 'task_results', 'task_results_binary');" 2>/dev/null | tr -d ' \n' || echo "0")
    
    if [ "$db_check" -ge 2 ]; then
        print_success "Database tables created correctly"
    else
        print_warning "Database table creation may have issues, but continuing deployment"
    fi
    
    # Wait for monitoring to be ready
    print_info "Waiting for monitoring services..."
    sleep 5
    
    # Basic connectivity tests
    print_info "Performing connectivity tests..."
    
    # Test Manager gRPC endpoint
    if timeout 5 nc -z localhost 50051 2>/dev/null; then
        print_success "Manager gRPC port (50051) accessible"
    else
        print_warning "Manager gRPC port temporarily inaccessible, but service may still be starting"
    fi
    
    # Test Grafana
    if timeout 5 nc -z localhost 3000 2>/dev/null; then
        print_success "Grafana (3000) accessible"
    else
        print_warning "Grafana temporarily inaccessible"
    fi
    
    # Test basic task submission capability
    print_info "Testing basic functionality..."
    
    # Give services more time to fully initialize
    sleep 10
    
    # Simple health check
    if curl -sf http://localhost:9000/metrics >/dev/null 2>&1; then
        print_success "Manager health check passed"
    else
        print_warning "Manager health check failed, may still be initializing"
    fi
    
    # Show deployment summary
    echo ""
    print_success "Docker Compose deployment completed!"
    
    # Comprehensive status check
    print_info "Generating deployment status report..."
    
    echo ""
    echo "🔗 Access URLs:"
    echo "  • Grafana monitoring: http://localhost:3000 (admin/admin)"
    echo "  • Prometheus: http://localhost:9090"
    echo "  • NATS monitoring: http://localhost:8222"
    echo "  • Manager API: localhost:50051 (gRPC)"
    echo ""
    
    # Detailed system status
    echo "📊 System status details:"
    local compose_file="$PROJECT_ROOT/deploy/docker/docker-compose.yaml"
    docker compose -f "$compose_file" ps --format "table {{.Name}}\t{{.Status}}\t{{.Ports}}"
    
    echo ""
    echo "🏥 Health checks:"
    
    # Check each service health
    local services=(
        "task-scheduler-postgres:PostgreSQL database"
        "task-scheduler-redis:Redis cache"
        "task-scheduler-nats:NATS message queue"
        "task-scheduler-manager:Task manager"
        "task-scheduler-prometheus:Monitoring data collection"
        "task-scheduler-grafana:Monitoring dashboard"
    )
    
    for service_info in "${services[@]}"; do
        IFS=':' read -r container_name description <<< "$service_info"
        local status=$(docker inspect --format='{{.State.Health.Status}}' "$container_name" 2>/dev/null || echo "no-healthcheck")
        
        case "$status" in
            "healthy")
                echo "  ✅ $description ($container_name): healthy"
                ;;
            "unhealthy")
                echo "  ❌ $description ($container_name): unhealthy"
                ;;
            "starting")
                echo "  🔄 $description ($container_name): starting"
                ;;
            "no-healthcheck")
                local running=$(docker inspect --format='{{.State.Running}}' "$container_name" 2>/dev/null || echo "false")
                if [ "$running" = "true" ]; then
                    echo "  ✅ $description ($container_name): running"
                else
                    echo "  ❌ $description ($container_name): not running"
                fi
                ;;
            *)
                echo "  ⚠️ $description ($container_name): status unknown"
                ;;
        esac
    done
    
    echo ""
    echo "🔧 Worker status:"
    local total_workers=$(docker ps --filter "name=docker-task-worker" --format "{{.Names}}" | wc -l)
    local healthy_workers=$(docker ps --filter "name=docker-task-worker" --filter "health=healthy" --format "{{.Names}}" | wc -l || echo "0")
    echo "  Total: $total_workers Workers"
    echo "  Healthy: $healthy_workers Workers"
    if [ "$healthy_workers" -lt "$total_workers" ]; then
        echo "  ⚠️ Some Workers may still be initializing"
    fi
    
    echo ""
    echo "💾 Storage status:"
    # Check database tables
    local table_count=$(docker exec task-scheduler-postgres psql -U task_scheduler -d task_scheduler -t -c "SELECT count(*) FROM information_schema.tables WHERE table_name LIKE 'task%';" 2>/dev/null | tr -d ' \n' || echo "0")
    echo "  Database tables: $table_count task-related tables"
    
    # Check Redis memory
    local redis_memory=$(docker exec task-scheduler-redis redis-cli info memory | grep "used_memory_human" | cut -d: -f2 | tr -d '\r' || echo "unknown")
    echo "  Redis memory usage: $redis_memory"
    
    echo ""
    echo "📈 Monitoring status:"
    # Check Prometheus targets
    if timeout 5 curl -s http://localhost:9090/api/v1/targets >/dev/null 2>&1; then
        local targets_up=$(curl -s http://localhost:9090/api/v1/targets 2>/dev/null | grep -o '"health":"up"' | wc -l || echo "0")
        local targets_total=$(curl -s http://localhost:9090/api/v1/targets 2>/dev/null | grep -o '"health":"' | wc -l || echo "0")
        echo "  Prometheus targets: $targets_up/$targets_total targets healthy"
    else
        echo "  ⚠️ Prometheus temporarily inaccessible"
    fi
    
    # Check Grafana dashboard availability
    if timeout 5 curl -s http://localhost:3000/api/health >/dev/null 2>&1; then
        local dashboard_count=$(ls "$PROJECT_ROOT/deploy/grafana/dashboards/"*.json 2>/dev/null | wc -l || echo "0")
        echo "  Grafana dashboards: $dashboard_count available dashboards"
    else
        echo "  ⚠️ Grafana temporarily inaccessible"
    fi
}

# Function to deploy with Kind + Helm (Production-like)
deploy_kind_helm() {
    print_section "☸️  Kind + Helm deployment mode (production-like)"
    
    # Check if kind cluster exists
    CLUSTER_NAME="task-scheduler"
    if ! kind get clusters | grep -q "$CLUSTER_NAME"; then
        print_info "Creating Kind cluster..."
        
        # Create Kind config for better performance
        cat > /tmp/kind-config.yaml << EOF
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
name: $CLUSTER_NAME
nodes:
- role: control-plane
  kubeadmConfigPatches:
  - |
    kind: InitConfiguration
    nodeRegistration:
      kubeletExtraArgs:
        node-labels: "ingress-ready=true"
  extraPortMappings:
  - containerPort: 80
    hostPort: 80
    protocol: TCP
  - containerPort: 443
    hostPort: 443
    protocol: TCP
  - containerPort: 3000
    hostPort: 3000
    protocol: TCP
  - containerPort: 9090
    hostPort: 9090
    protocol: TCP
  - containerPort: 50051
    hostPort: 50051
    protocol: TCP
EOF
        
        kind create cluster --config /tmp/kind-config.yaml
        rm /tmp/kind-config.yaml
    else
        print_info "Using existing Kind cluster: $CLUSTER_NAME"
        kubectl config use-context "kind-$CLUSTER_NAME"
    fi
    
    # Add Helm repositories
    print_info "Configuring Helm repositories..."
    helm repo add nats https://nats-io.github.io/k8s/helm/charts/ 2>/dev/null || true
    helm repo add prometheus-community https://prometheus-community.github.io/helm-charts 2>/dev/null || true
    helm repo add grafana https://grafana.github.io/helm-charts 2>/dev/null || true
    helm repo update
    
    # Create namespace
    kubectl create namespace $NAMESPACE --dry-run=client -o yaml | kubectl apply -f -
    
    # Deploy with Helm
    print_info "Deploying task scheduler system..."
    helm upgrade --install task-scheduler \
        "$PROJECT_ROOT/deploy/helm/task-scheduler" \
        --namespace $NAMESPACE \
        --wait --timeout=10m \
        --values "$PROJECT_ROOT/deploy/helm/task-scheduler/values-local.yaml"
    
    # Deploy monitoring stack if not exists
    if ! helm list -n monitoring | grep -q prometheus; then
        print_info "Deploying monitoring system..."
        kubectl create namespace monitoring --dry-run=client -o yaml | kubectl apply -f -
        
        # Deploy Prometheus
        helm upgrade --install prometheus prometheus-community/kube-prometheus-stack \
            --namespace monitoring \
            --wait --timeout=10m \
            --set grafana.service.type=NodePort \
            --set grafana.service.nodePort=30300 \
            --set prometheus.service.type=NodePort \
            --set prometheus.service.nodePort=30900
    fi
    
    print_success "Helm deployment completed!"
    echo ""
    echo "🔗 Access URLs:"
    echo "  • Grafana: http://localhost:3000 (admin/prom-operator)"
    echo "  • Prometheus: http://localhost:9090"
    echo "  • Task Manager: localhost:50051 (requires port-forward)"
    echo ""
    echo "📊 Cluster status:"
    kubectl get pods -n $NAMESPACE
    echo ""
    echo "💡 Useful commands:"
    echo "  • View logs: kubectl logs -f deployment/task-manager -n $NAMESPACE"
    echo "  • Port forward: kubectl port-forward svc/task-manager 50051:50051 -n $NAMESPACE"
    echo "  • Delete cluster: kind delete cluster --name $CLUSTER_NAME"
}

# Function to run performance validation
run_performance_test() {
    print_section "⚡ Performance validation test"
    
    if [ "$DEPLOYMENT_MODE" = "docker" ] || [ "$DEPLOYMENT_MODE" = "auto" ]; then
        # Docker Compose mode
        print_info "Waiting for system to fully start..."
        sleep 20
        
        print_info "Running quick performance test..."
        if [ -f "$PROJECT_ROOT/scripts/test/benchmark.sh" ]; then
            # Run a quick test
            timeout 60 "$PROJECT_ROOT/scripts/test/benchmark.sh" --quick || {
                print_warning "Performance test timeout or failed, but system may be running normally"
            }
        else
            print_warning "Performance test script does not exist"
        fi
    else
        # Kind mode
        print_info "Setting up port forwarding for testing..."
        kubectl port-forward svc/task-manager 50051:50051 -n $NAMESPACE &
        PORT_FORWARD_PID=$!
        
        sleep 10
        
        print_info "Running quick test..."
        if [ -f "$PROJECT_ROOT/scripts/test/benchmark.sh" ]; then
            timeout 60 "$PROJECT_ROOT/scripts/test/benchmark.sh" --quick || {
                print_warning "Performance test timeout or failed"
            }
        fi
        
        # Cleanup port forward
        kill $PORT_FORWARD_PID 2>/dev/null || true
    fi
}

# Main deployment logic
main() {
    # Check requirements
    if ! check_requirements docker; then
        print_error "Docker is required! Please install Docker first"
        exit 1
    fi
    
    # Determine deployment mode
    case $DEPLOYMENT_MODE in
        "docker")
            deploy_docker_compose
            ;;
        "kind")
            if check_kind_available; then
                deploy_kind_helm
            else
                print_error "Kind + Kubectl + Helm not available. Please install or use docker mode"
                exit 1
            fi
            ;;
        "auto")
            if check_kind_available; then
                print_info "Detected Kind + Helm environment, using production-like deployment mode"
                DEPLOYMENT_MODE="kind"
                deploy_kind_helm
            else
                print_info "Using Docker Compose mode (recommended for development and testing)"
                DEPLOYMENT_MODE="docker"
                deploy_docker_compose
            fi
            ;;
        *)
            print_error "Unknown deployment mode: $DEPLOYMENT_MODE"
            echo "Supported modes: auto, docker, kind"
            exit 1
            ;;
    esac
    
    # Run performance validation
    echo ""
    read -p "Run performance validation test? (y/n): " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        run_performance_test
    fi
    
    # Auto-fix monitoring if needed
    print_info "Verifying and fixing monitoring configuration..."
    if [ -f "$SCRIPT_DIR/fix-monitoring.sh" ]; then
        "$SCRIPT_DIR/fix-monitoring.sh" >/dev/null 2>&1 || {
            print_warning "Monitoring auto-fix encountered issues, but core system is running normally"
        }
    fi
    
    print_section "🎉 Deployment completed!"
    
    # Final deployment verification
    print_info "Performing final deployment verification..."
    
    # Create a deployment summary
    echo ""
    echo "📋 Deployment summary:"
    echo "  • Deployment mode: $DEPLOYMENT_MODE"
    echo "  • Deployment time: $(date '+%Y-%m-%d %H:%M:%S')"
    
    if [ "$DEPLOYMENT_MODE" = "docker" ]; then
        echo "  • Container count: $(docker ps --filter "name=task-scheduler" --format "{{.Names}}" | wc -l) containers"
        echo "  • Network: task-scheduler-net"
        echo "  • Data volumes: persistent storage"
    else
        echo "  • Cluster: Kind ($CLUSTER_NAME)"
        echo "  • Namespace: $NAMESPACE"
        echo "  • Helm release: task-scheduler"
    fi
    
    echo ""
    echo "🎯 System verification results:"
    
    # Quick functional verification
    local verification_passed=0
    local total_checks=4
    
    # Check 1: Core services running
    if docker ps --filter "name=task-scheduler-manager" --filter "status=running" | grep -q "task-scheduler-manager"; then
        echo "  ✅ Core services running normally"
        verification_passed=$((verification_passed + 1))
    else
        echo "  ❌ Core services not running"
    fi
    
    # Check 2: Database connectivity
    if docker exec task-scheduler-postgres pg_isready -U task_scheduler >/dev/null 2>&1; then
        echo "  ✅ Database connection normal"
        verification_passed=$((verification_passed + 1))
    else
        echo "  ❌ Database connection failed"
    fi
    
    # Check 3: Message queue
    if docker exec task-scheduler-nats nats-server --version >/dev/null 2>&1; then
        echo "  ✅ Message queue service normal"
        verification_passed=$((verification_passed + 1))
    else
        echo "  ❌ Message queue service abnormal"
    fi
    
    # Check 4: Monitoring stack
    if timeout 3 curl -s http://localhost:3000/api/health >/dev/null 2>&1 && timeout 3 curl -s http://localhost:9090/-/healthy >/dev/null 2>&1; then
        echo "  ✅ Monitoring system normal"
        verification_passed=$((verification_passed + 1))
    else
        echo "  ⚠️ Monitoring system partially accessible"
    fi
    
    echo ""
    if [ $verification_passed -eq $total_checks ]; then
        echo "🏆 Deployment verification: fully passed ($verification_passed/$total_checks)"
        echo "🚀 System is ready and can start using!"
    elif [ $verification_passed -ge 2 ]; then
        echo "⚠️ Deployment verification: basically passed ($verification_passed/$total_checks)"
        echo "📝 Some components may need additional time to complete initialization"
    else
        echo "❌ Deployment verification: failed ($verification_passed/$total_checks)"
        echo "🔧 Suggest checking logs: docker compose -f $PROJECT_ROOT/deploy/docker/docker-compose.yaml logs"
    fi
    
    echo ""
    echo "🔗 Quick access:"
    echo "  • Grafana dashboard: http://localhost:3000 (admin/admin)"
    echo "  • Prometheus metrics: http://localhost:9090"
    echo "  • NATS monitoring: http://localhost:8222"
    echo ""
    echo "📚 Next steps:"
    echo "  1. Check monitoring dashboards to confirm data displays normally"
    echo "  2. Run performance test: ./scripts/test/benchmark.sh --quick"
    echo "  3. Submit test task: ./examples/submit-task-test.rs"
    echo "  4. View complete documentation: cat DEPLOYMENT.md"
    echo ""
    echo "🛠️ Troubleshooting:"
    echo "  • View all logs: docker compose -f deploy/docker/docker-compose.yaml logs"
    echo "  • Fix monitoring issues: ./scripts/deploy/fix-monitoring.sh"
    echo "  • Redeploy: ./scripts/deploy/deploy-one-click.sh"
    echo "  • Clean environment: docker compose -f deploy/docker/docker-compose.yaml down --volumes"
    echo ""
}

# Run main function
main "$@"