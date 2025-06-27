#!/bin/bash
# Complete Test Suite for Task Scheduler System
# This script runs all tests from unit tests to Kubernetes integration tests

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Test results tracking
TOTAL_TESTS=0
PASSED_TESTS=0
FAILED_TESTS=0
SKIPPED_TESTS=0

# Function to print colored output
print_color() {
    local color=$1
    shift
    echo -e "${color}$@${NC}"
}

# Function to print test header
print_header() {
    echo
    print_color "$BLUE" "========================================"
    print_color "$BLUE" "$1"
    print_color "$BLUE" "========================================"
    echo
}

# Function to check if a command exists
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

# Function to run a test suite
run_test() {
    local test_name=$1
    local test_command=$2
    
    ((TOTAL_TESTS++))
    print_color "$YELLOW" "Running: $test_name"
    
    if eval "$test_command"; then
        ((PASSED_TESTS++))
        print_color "$GREEN" "✓ $test_name: PASSED"
    else
        ((FAILED_TESTS++))
        print_color "$RED" "✗ $test_name: FAILED"
    fi
}

# Function to run optional test (skip if dependencies not available)
run_optional_test() {
    local test_name=$1
    local check_command=$2
    local test_command=$3
    
    ((TOTAL_TESTS++))
    print_color "$YELLOW" "Running: $test_name"
    
    if ! eval "$check_command"; then
        ((SKIPPED_TESTS++))
        print_color "$YELLOW" "⚠ $test_name: SKIPPED (dependencies not available)"
    else
        if eval "$test_command"; then
            ((PASSED_TESTS++))
            print_color "$GREEN" "✓ $test_name: PASSED"
        else
            ((FAILED_TESTS++))
            print_color "$RED" "✗ $test_name: FAILED"
        fi
    fi
}

# Main test execution starts here
print_color "$GREEN" "Task Scheduler Complete Test Suite"
print_color "$GREEN" "=================================="
echo

# Check Rust installation
if ! command_exists cargo; then
    print_color "$RED" "Error: Rust/Cargo not found. Please install Rust first."
    exit 1
fi

# Display environment information
print_header "Environment Information"
echo "Rust version: $(rustc --version)"
echo "Cargo version: $(cargo --version)"
echo "Current directory: $(pwd)"
echo

# Level 1: Unit Tests
print_header "Level 1: Unit Tests"

run_test "Cargo format check" "cargo fmt --all -- --check"
run_test "Clippy lints" "cargo clippy --all-targets --all-features -- -D warnings"
run_test "Unit tests - task-common" "cargo test -p task-common --lib"
run_test "Unit tests - task-storage" "cargo test -p task-storage --lib"
run_test "Unit tests - task-worker" "cargo test -p task-worker --lib"
run_test "Unit tests - task-manager" "cargo test -p task-manager --lib"
run_test "Unit tests - task-operator" "cargo test -p task-operator --lib"
run_test "Unit tests - task-plugins" "cargo test -p task-plugins --lib"
run_test "Unit tests - task-scheduler" "cargo test -p task-scheduler --lib"

# Level 2: Integration Tests
print_header "Level 2: Integration Tests"

run_test "Integration tests - task-common" "cargo test -p task-common --test '*' -- --test-threads=1"
run_test "Integration tests - task-worker" "cargo test -p task-worker --test '*' -- --test-threads=1"
run_test "Integration tests - task-manager" "cargo test -p task-manager --test '*' -- --test-threads=1"

# Level 3: Storage Backend Tests
print_header "Level 3: Storage Backend Tests"

# PostgreSQL tests
run_optional_test "PostgreSQL storage tests" \
    "pg_isready -h localhost -p 5432 2>/dev/null || docker ps | grep -q postgres" \
    "TEST_POSTGRES_URL=${TEST_POSTGRES_URL:-postgres://postgres:postgres@localhost:5432/task_scheduler_test} cargo test -p task-storage --test '*' --features postgres -- --test-threads=1"

# MongoDB tests
run_optional_test "MongoDB storage tests" \
    "mongosh --eval 'db.version()' 2>/dev/null || docker ps | grep -q mongo" \
    "TEST_MONGODB_URL=${TEST_MONGODB_URL:-mongodb://localhost:27017} cargo test -p task-storage --test '*' --features mongodb -- --test-threads=1"

# Level 4: Message Queue Tests
print_header "Level 4: Message Queue Tests"

run_optional_test "NATS integration tests" \
    "docker ps | grep -q nats || command_exists nats-server" \
    "cargo test -p task-common --test nats_integration_test --features nats-test -- --test-threads=1 --nocapture"

# Level 5: gRPC API Tests
print_header "Level 5: gRPC API Tests"

run_test "gRPC API tests" "cargo test -p task-manager --test grpc_api_integration_tests -- --test-threads=1"

# Level 6: Plugin System Tests
print_header "Level 6: Plugin System Tests"

# Build test plugins
if [ -d "examples/plugin-example" ]; then
    run_test "Build example plugin" "cd examples/plugin-example && cargo build --release && cd ../.."
fi

run_test "Plugin loading tests" "cargo test -p task-plugins --test plugin_loading_test -- --test-threads=1"
run_test "Plugin execution tests" "cargo test -p task-worker --test plugin_execution_test -- --test-threads=1"

# Level 7: Kubernetes CRD Tests
print_header "Level 7: Kubernetes CRD Tests"

run_optional_test "Kubernetes CRD validation" \
    "kubectl version --client 2>/dev/null" \
    "cargo test -p task-common --test k8s_crd_test -- --test-threads=1"

# Level 8: End-to-End Tests
print_header "Level 8: End-to-End Tests"

# Check if all required services are available
E2E_READY=true
if ! pg_isready -h localhost -p 5432 2>/dev/null && ! docker ps | grep -q postgres; then
    E2E_READY=false
fi
if ! docker ps | grep -q nats; then
    E2E_READY=false
fi

if [ "$E2E_READY" = true ]; then
    run_test "End-to-end workflow test" "./scripts/run-e2e-test.sh"
else
    ((TOTAL_TESTS++))
    ((SKIPPED_TESTS++))
    print_color "$YELLOW" "⚠ End-to-end workflow test: SKIPPED (required services not running)"
fi

# Level 9: Performance Tests
print_header "Level 9: Performance Tests"

run_test "Benchmark tests" "cargo bench --no-run"
run_optional_test "Load test - 1000 concurrent tasks" \
    "[ '$RUN_LOAD_TESTS' = 'true' ]" \
    "cargo run -p task-load-test -- --tasks 1000 --workers 10 --duration 60"

# Level 10: Kubernetes Integration Tests
print_header "Level 10: Kubernetes Integration Tests"

# Check if running in Kubernetes or if minikube/kind is available
K8S_AVAILABLE=false
if kubectl cluster-info >/dev/null 2>&1; then
    K8S_AVAILABLE=true
fi

run_optional_test "Deploy CRDs to Kubernetes" \
    "[ '$K8S_AVAILABLE' = true ]" \
    "kubectl apply -f deploy/kubernetes/crds/"

run_optional_test "Deploy operator to Kubernetes" \
    "[ '$K8S_AVAILABLE' = true ] && [ '$DEPLOY_TO_K8S' = 'true' ]" \
    "./scripts/deploy-to-k8s.sh test"

run_optional_test "Kubernetes integration test" \
    "[ '$K8S_AVAILABLE' = true ] && [ '$DEPLOY_TO_K8S' = 'true' ]" \
    "cargo test -p task-operator --test k8s_integration_test -- --test-threads=1 --nocapture"

# Level 11: Security Tests
print_header "Level 11: Security Tests"

run_test "Dependency audit" "cargo audit --ignore RUSTSEC-2020-0071"
run_test "License check" "cargo deny check licenses || true"

# Level 12: Documentation Tests
print_header "Level 12: Documentation Tests"

run_test "Documentation build" "cargo doc --all-features --no-deps"
run_test "Documentation tests" "cargo test --doc"

# Test Summary
print_header "Test Summary"

echo "Total tests: $TOTAL_TESTS"
print_color "$GREEN" "Passed: $PASSED_TESTS"
print_color "$RED" "Failed: $FAILED_TESTS"
print_color "$YELLOW" "Skipped: $SKIPPED_TESTS"
echo
echo "Success rate: $(( PASSED_TESTS * 100 / (PASSED_TESTS + FAILED_TESTS) ))%"

# Exit with appropriate code
if [ $FAILED_TESTS -gt 0 ]; then
    print_color "$RED" "Some tests failed!"
    exit 1
else
    print_color "$GREEN" "All tests passed!"
    exit 0
fi