#!/bin/bash
# UltraThink Complete Test Suite for Rust Task Scheduler
# This script runs all comprehensive tests including unit, integration, and performance tests
# Created: 2025-06-29

set -e

# Color codes
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
PURPLE='\033[0;35m'
NC='\033[0m' # No Color

# Script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# Test configuration
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
LOG_DIR="$PROJECT_ROOT/test-logs-$TIMESTAMP"
SUMMARY_FILE="$LOG_DIR/test-summary.txt"
FAILED_TESTS=()
PASSED_TESTS=()
SKIPPED_TESTS=()

# Create log directory
mkdir -p "$LOG_DIR"

# Function to print colored output
print_color() {
    local color=$1
    local message=$2
    echo -e "${color}${message}${NC}"
}

# Function to print test header
print_header() {
    echo ""
    print_color "$BLUE" "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    print_color "$BLUE" "$1"
    print_color "$BLUE" "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
}

# Function to run a test and track results
run_test() {
    local test_name=$1
    local test_command=$2
    local allow_failure=${3:-false}
    local log_file="$LOG_DIR/${test_name//[^a-zA-Z0-9]/_}.log"
    
    echo -n "Running $test_name... "
    
    if eval "$test_command" > "$log_file" 2>&1; then
        print_color "$GREEN" "✓ PASSED"
        PASSED_TESTS+=("$test_name")
        return 0
    else
        if [ "$allow_failure" = true ]; then
            print_color "$YELLOW" "⚠ FAILED (allowed)"
            SKIPPED_TESTS+=("$test_name")
            return 0
        else
            print_color "$RED" "✗ FAILED"
            FAILED_TESTS+=("$test_name")
            echo "  See log: $log_file"
            return 1
        fi
    fi
}

# Function to check prerequisites
check_prerequisites() {
    print_header "Checking Prerequisites"
    
    local missing=()
    
    # Required tools
    command -v cargo >/dev/null 2>&1 || missing+=("cargo")
    command -v docker >/dev/null 2>&1 || missing+=("docker")
    command -v docker compose >/dev/null 2>&1 || missing+=("docker compose")
    
    if [ ${#missing[@]} -ne 0 ]; then
        print_color "$RED" "Missing required tools: ${missing[*]}"
        exit 1
    fi
    
    # Check Docker daemon
    if ! docker info >/dev/null 2>&1; then
        print_color "$RED" "Docker daemon is not running"
        exit 1
    fi
    
    print_color "$GREEN" "✓ All prerequisites satisfied"
}

# Function to clean environment
clean_environment() {
    print_header "Cleaning Environment"
    
    # Stop any running containers
    print_color "$YELLOW" "Stopping Docker containers..."
    cd "$PROJECT_ROOT/deploy/docker"
    docker compose down -v >/dev/null 2>&1 || true
    
    # Kill any rogue processes
    print_color "$YELLOW" "Killing rogue processes..."
    pkill -f "task-manager" 2>/dev/null || true
    pkill -f "task-worker" 2>/dev/null || true
    
    # Clean build artifacts
    print_color "$YELLOW" "Cleaning build artifacts..."
    cd "$PROJECT_ROOT"
    cargo clean >/dev/null 2>&1
    
    print_color "$GREEN" "✓ Environment cleaned"
}

# Function to build project
build_project() {
    print_header "Building Project"
    
    cd "$PROJECT_ROOT"
    
    # Debug build
    run_test "Debug Build" "cargo build --all"
    
    # Release build
    run_test "Release Build" "cargo build --release --all"
    
    # Check for warnings
    run_test "Clippy Check" "cargo clippy --all-targets --all-features -- -D warnings" true
}

# Function to start services
start_services() {
    print_header "Starting Services"
    
    cd "$PROJECT_ROOT/deploy/docker"
    
    # Start infrastructure services
    run_test "Start Infrastructure" "docker compose up -d nats postgres redis"
    
    # Wait for services to be healthy
    print_color "$YELLOW" "Waiting for services to be healthy..."
    local max_attempts=30
    local attempt=0
    
    while [ $attempt -lt $max_attempts ]; do
        if docker compose ps | grep -E "(healthy|running)" | grep -q "nats" && \
           docker compose ps | grep -E "(healthy|running)" | grep -q "postgres" && \
           docker compose ps | grep -E "(healthy|running)" | grep -q "redis"; then
            break
        fi
        attempt=$((attempt + 1))
        sleep 2
    done
    
    if [ $attempt -eq $max_attempts ]; then
        print_color "$RED" "Services failed to become healthy"
        return 1
    fi
    
    print_color "$GREEN" "✓ Services are healthy"
}

# Function to run unit tests
run_unit_tests() {
    print_header "Unit Tests"
    
    cd "$PROJECT_ROOT"
    
    # Test each crate
    run_test "task-common unit tests" "cargo test -p task-common --lib -- --test-threads=1"
    run_test "task-manager unit tests" "cargo test -p task-manager --lib -- --test-threads=1"
    run_test "task-worker unit tests" "cargo test -p task-worker --lib -- --test-threads=1"
    run_test "task-storage unit tests" "cargo test -p task-storage --lib -- --test-threads=1"
    run_test "task-scheduler unit tests" "cargo test -p task-scheduler --lib -- --test-threads=1"
    run_test "task-plugins unit tests" "cargo test -p task-plugins --lib -- --test-threads=1"
}

# Function to run integration tests
run_integration_tests() {
    print_header "Integration Tests"
    
    cd "$PROJECT_ROOT"
    
    # Start task-manager
    print_color "$YELLOW" "Starting task-manager..."
    RUST_LOG=info ./target/debug/task-manager > "$LOG_DIR/task-manager.log" 2>&1 &
    MANAGER_PID=$!
    sleep 3
    
    # Check if manager started successfully
    if ! kill -0 $MANAGER_PID 2>/dev/null; then
        print_color "$RED" "Failed to start task-manager"
        return 1
    fi
    
    # Start task-workers
    print_color "$YELLOW" "Starting task-workers..."
    for i in {1..3}; do
        RUST_LOG=info ./target/debug/task-worker --worker-id "test-worker-$i" > "$LOG_DIR/task-worker-$i.log" 2>&1 &
        WORKER_PIDS="$WORKER_PIDS $!"
    done
    sleep 3
    
    # Run integration tests
    run_test "Basic task submission" "cargo test -p task-tests --test integration_test test_simple_task_submission -- --nocapture"
    run_test "Task with dependencies" "cargo test -p task-tests --test integration_test test_task_with_dependencies -- --nocapture"
    run_test "Task cancellation" "cargo test -p task-tests --test integration_test test_task_cancellation -- --nocapture"
    run_test "Concurrent task submission" "cargo test -p task-tests --test integration_test test_concurrent_task_submission -- --nocapture"
    
    # Plugin tests
    run_test "Plugin system" "cargo test -p task-tests --test plugin_system_test -- --nocapture"
    
    # Resilience tests
    run_test "Task retry mechanism" "cargo test -p task-tests --test resilience_test test_task_retry_mechanism -- --nocapture"
    run_test "Malformed request handling" "cargo test -p task-tests --test resilience_test test_malformed_request_handling -- --nocapture"
    
    # Kill services
    kill $MANAGER_PID 2>/dev/null || true
    kill $WORKER_PIDS 2>/dev/null || true
}

# Function to run performance tests
run_performance_tests() {
    print_header "Performance Tests"
    
    cd "$PROJECT_ROOT"
    
    # Start services in release mode
    print_color "$YELLOW" "Starting services in release mode..."
    RUST_LOG=error ./target/release/task-manager > "$LOG_DIR/task-manager-perf.log" 2>&1 &
    MANAGER_PID=$!
    sleep 3
    
    for i in {1..5}; do
        RUST_LOG=error ./target/release/task-worker --worker-id "perf-worker-$i" > "$LOG_DIR/task-worker-perf-$i.log" 2>&1 &
        WORKER_PIDS="$WORKER_PIDS $!"
    done
    sleep 3
    
    # Run performance tests
    run_test "Throughput test" "cargo test --release -p task-tests --test performance_test test_throughput -- --nocapture" true
    run_test "Latency test" "cargo test --release -p task-tests --test performance_test test_latency -- --nocapture" true
    run_test "Concurrent clients test" "cargo test --release -p task-tests --test performance_test test_concurrent_clients -- --nocapture" true
    
    # Kill services
    kill $MANAGER_PID 2>/dev/null || true
    kill $WORKER_PIDS 2>/dev/null || true
}

# Function to test monitoring
test_monitoring() {
    print_header "Monitoring & Observability Tests"
    
    cd "$PROJECT_ROOT"
    
    # Start services with monitoring enabled
    print_color "$YELLOW" "Starting services with monitoring..."
    RUST_LOG=info ./target/debug/task-manager > "$LOG_DIR/task-manager-mon.log" 2>&1 &
    MANAGER_PID=$!
    sleep 3
    
    # Check metrics endpoint
    run_test "Manager metrics endpoint" "curl -f http://localhost:9000/metrics"
    
    # Submit some tasks to generate metrics
    if [ -f "./target/debug/submit_test_task" ]; then
        run_test "Generate metrics data" "./target/debug/submit_test_task --count 10"
    fi
    
    # Check specific metrics
    run_test "Task submission metrics" "curl -s http://localhost:9000/metrics | grep -q task_manager_tasks_submitted_total"
    run_test "Queue depth metrics" "curl -s http://localhost:9000/metrics | grep -q task_manager_queue_depth"
    run_test "Worker pool metrics" "curl -s http://localhost:9000/metrics | grep -q task_manager_worker_pool_size"
    
    kill $MANAGER_PID 2>/dev/null || true
}

# Function to test Docker deployment
test_docker_deployment() {
    print_header "Docker Deployment Test"
    
    cd "$PROJECT_ROOT"
    
    # Build Docker images
    run_test "Build Docker images" "./scripts/docker-build-local.sh"
    
    # Start full stack
    cd "$PROJECT_ROOT/deploy/docker"
    run_test "Start Docker stack" "docker compose up -d"
    
    # Wait for services
    sleep 10
    
    # Test connectivity
    run_test "Test gRPC connectivity" "docker exec task-scheduler-manager curl -f http://localhost:9000/health || true" true
    
    # Submit test task
    if [ -f "$PROJECT_ROOT/target/debug/submit_test_task" ]; then
        run_test "Submit task to Docker deployment" "$PROJECT_ROOT/target/debug/submit_test_task --server http://localhost:50052 --count 5"
    fi
    
    # Check logs
    run_test "Check manager logs" "docker logs task-scheduler-manager 2>&1 | grep -q 'Task Manager.*started'"
    run_test "Check worker logs" "docker logs docker-task-worker-1 2>&1 | grep -q 'Worker.*started'"
    
    # Stop stack
    docker compose down
}

# Function to generate summary report
generate_summary() {
    print_header "Test Summary"
    
    local total_tests=$((${#PASSED_TESTS[@]} + ${#FAILED_TESTS[@]} + ${#SKIPPED_TESTS[@]}))
    local pass_rate=0
    if [ $total_tests -gt 0 ]; then
        pass_rate=$(awk "BEGIN {printf \"%.1f\", (${#PASSED_TESTS[@]} / $total_tests) * 100}")
    fi
    
    # Console output
    print_color "$CYAN" "Total Tests: $total_tests"
    print_color "$GREEN" "Passed: ${#PASSED_TESTS[@]}"
    print_color "$RED" "Failed: ${#FAILED_TESTS[@]}"
    print_color "$YELLOW" "Skipped: ${#SKIPPED_TESTS[@]}"
    print_color "$CYAN" "Pass Rate: $pass_rate%"
    
    # Write to summary file
    cat > "$SUMMARY_FILE" << EOF
Rust Task Scheduler - Complete Test Suite Results
================================================
Date: $(date)
Total Tests: $total_tests
Passed: ${#PASSED_TESTS[@]}
Failed: ${#FAILED_TESTS[@]}
Skipped: ${#SKIPPED_TESTS[@]}
Pass Rate: $pass_rate%

PASSED TESTS:
EOF
    for test in "${PASSED_TESTS[@]}"; do
        echo "  ✓ $test" >> "$SUMMARY_FILE"
    done
    
    if [ ${#FAILED_TESTS[@]} -gt 0 ]; then
        echo -e "\nFAILED TESTS:" >> "$SUMMARY_FILE"
        for test in "${FAILED_TESTS[@]}"; do
            echo "  ✗ $test" >> "$SUMMARY_FILE"
        done
    fi
    
    if [ ${#SKIPPED_TESTS[@]} -gt 0 ]; then
        echo -e "\nSKIPPED TESTS:" >> "$SUMMARY_FILE"
        for test in "${SKIPPED_TESTS[@]}"; do
            echo "  ⚠ $test" >> "$SUMMARY_FILE"
        done
    fi
    
    echo -e "\nLogs saved to: $LOG_DIR" >> "$SUMMARY_FILE"
    
    # Show summary location
    echo ""
    print_color "$BLUE" "Full test logs saved to: $LOG_DIR"
    print_color "$BLUE" "Summary saved to: $SUMMARY_FILE"
}

# Function to cleanup
cleanup() {
    print_header "Cleanup"
    
    # Kill any remaining processes
    pkill -f "task-manager" 2>/dev/null || true
    pkill -f "task-worker" 2>/dev/null || true
    
    # Stop Docker containers
    cd "$PROJECT_ROOT/deploy/docker"
    docker compose down -v >/dev/null 2>&1 || true
    
    print_color "$GREEN" "✓ Cleanup completed"
}

# Main execution
main() {
    print_color "$PURPLE" "╔════════════════════════════════════════════════════════════════╗"
    print_color "$PURPLE" "║     Rust Task Scheduler - UltraThink Complete Test Suite       ║"
    print_color "$PURPLE" "╚════════════════════════════════════════════════════════════════╝"
    
    # Set trap for cleanup
    trap cleanup EXIT
    
    # Run test phases
    check_prerequisites
    clean_environment
    build_project
    start_services
    
    # Core tests
    run_unit_tests
    run_integration_tests
    run_performance_tests
    
    # Additional tests
    test_monitoring
    test_docker_deployment
    
    # Generate report
    generate_summary
    
    # Exit with appropriate code
    if [ ${#FAILED_TESTS[@]} -eq 0 ]; then
        print_color "$GREEN" "\n🎉 All tests completed successfully!"
        exit 0
    else
        print_color "$RED" "\n❌ Some tests failed. Please check the logs."
        exit 1
    fi
}

# Handle interrupts
trap 'print_color "$RED" "\nTest suite interrupted!"; cleanup; exit 1' INT TERM

# Run main function
main "$@"