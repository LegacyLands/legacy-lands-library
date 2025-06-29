#!/bin/bash
# Master test runner for Rust Task Scheduler
# Runs all test suites and generates a comprehensive report

set -e

# Color codes
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Script directory
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
UTILS_DIR="$SCRIPT_DIR/.."

# Test results
PASSED_TESTS=0
FAILED_TESTS=0
SKIPPED_TESTS=0

# Log file
LOG_FILE="/tmp/test-run-$(date +%Y%m%d-%H%M%S).log"

# Function to print section header
print_header() {
    echo ""
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
}

# Function to run a test suite
run_test_suite() {
    local suite_name=$1
    local test_command=$2
    local allow_failure=${3:-false}
    
    echo -e "${CYAN}Running $suite_name...${NC}"
    
    if eval "$test_command" >> "$LOG_FILE" 2>&1; then
        echo -e "${GREEN}✓ $suite_name passed${NC}"
        ((PASSED_TESTS++))
        return 0
    else
        if [ "$allow_failure" = true ]; then
            echo -e "${YELLOW}⚠ $suite_name failed (allowed)${NC}"
            ((SKIPPED_TESTS++))
            return 0
        else
            echo -e "${RED}✗ $suite_name failed${NC}"
            ((FAILED_TESTS++))
            return 1
        fi
    fi
}

# Function to check prerequisites
check_prerequisites() {
    print_header "Checking Prerequisites"
    
    # Check for required tools
    local missing_tools=()
    
    command -v cargo >/dev/null 2>&1 || missing_tools+=("cargo")
    command -v docker >/dev/null 2>&1 || missing_tools+=("docker")
    command -v docker compose >/dev/null 2>&1 || missing_tools+=("docker-compose")
    
    if [ ${#missing_tools[@]} -ne 0 ]; then
        echo -e "${RED}Missing required tools: ${missing_tools[*]}${NC}"
        exit 1
    fi
    
    echo -e "${GREEN}✓ All required tools are installed${NC}"
}

# Function to setup test environment
setup_test_environment() {
    print_header "Setting Up Test Environment"
    
    cd "$PROJECT_ROOT"
    
    # Stop any existing services
    echo -e "${YELLOW}Stopping any existing services...${NC}"
    cd "$PROJECT_ROOT/deploy/docker"
    docker compose down -v >> "$LOG_FILE" 2>&1 || true
    
    # Start services
    echo -e "${YELLOW}Starting infrastructure services...${NC}"
    if ! docker compose up -d nats postgres redis >> "$LOG_FILE" 2>&1; then
        echo -e "${RED}Failed to start services!${NC}"
        echo "Check log file: $LOG_FILE"
        exit 1
    fi
    
    # Wait for services to be healthy
    sleep 10
    
    echo -e "${GREEN}✓ Test environment is ready${NC}"
}

# Function to teardown test environment
teardown_test_environment() {
    print_header "Cleaning Up Test Environment"
    
    echo -e "${YELLOW}Stopping all services...${NC}"
    cd "$PROJECT_ROOT/deploy/docker"
    docker compose down -v >> "$LOG_FILE" 2>&1 || true
    
    echo -e "${GREEN}✓ Cleanup completed${NC}"
}

# Function to build project
build_project() {
    print_header "Building Project"
    
    cd "$PROJECT_ROOT"
    
    echo -e "${YELLOW}Building all packages...${NC}"
    if cargo build --all >> "$LOG_FILE" 2>&1; then
        echo -e "${GREEN}✓ Build successful${NC}"
    else
        echo -e "${RED}✗ Build failed${NC}"
        echo "Check log file: $LOG_FILE"
        exit 1
    fi
}

# Function to run unit tests
run_unit_tests() {
    print_header "Running Unit Tests"
    
    cd "$PROJECT_ROOT"
    
    # Run unit tests for each crate
    run_test_suite "Common library tests" "cargo test -p task-common --lib"
    run_test_suite "Storage library tests" "cargo test -p task-storage --lib"
    run_test_suite "Manager library tests" "cargo test -p task-manager --lib"
    run_test_suite "Worker library tests" "cargo test -p task-worker --lib"
    run_test_suite "Plugins library tests" "cargo test -p task-plugins --lib"
    run_test_suite "Operator library tests" "cargo test -p task-operator --lib"
}

# Function to run storage tests
run_storage_tests() {
    print_header "Running Storage Tests"
    
    cd "$PROJECT_ROOT"
    
    # Set environment variables
    export TEST_POSTGRES_URL="postgres://postgres:postgres@localhost:5432/task_scheduler"
    
    run_test_suite "PostgreSQL storage tests" "cargo test -p task-storage --all-features -- --test-threads=1"
}

# Function to run integration tests
run_integration_tests() {
    print_header "Running Integration Tests"
    
    cd "$PROJECT_ROOT"
    
    # Set environment variables
    export GRPC_ADDRESS="http://localhost:50051"
    export NATS_URL="nats://localhost:4222"
    
    run_test_suite "Integration tests" "cargo test -p task-tests --test integration_test -- --test-threads=1"
}

# Function to run performance tests
run_performance_tests() {
    print_header "Running Performance Tests (Release Mode)"
    
    cd "$PROJECT_ROOT"
    
    # Build in release mode
    echo -e "${YELLOW}Building in release mode for performance tests...${NC}"
    cargo build --release --bin task-manager --bin task-worker >> "$LOG_FILE" 2>&1
    
    # Ensure services are still running
    cd "$PROJECT_ROOT/deploy/docker"
    docker compose ps >> "$LOG_FILE" 2>&1
    
    # Start release mode services
    GRPC_ADDRESS=0.0.0.0:50051 METRICS_ADDRESS=0.0.0.0:9091 NATS_URL=nats://localhost:4222 \
        ./target/release/task-manager > /tmp/task-manager-perf.log 2>&1 &
    MANAGER_PID=$!
    
    sleep 3
    
    MANAGER_ADDRESS=localhost:50051 NATS_URL=nats://localhost:4222 WORKER_ID=perf-worker-1 \
        ./target/release/task-worker > /tmp/task-worker-perf.log 2>&1 &
    WORKER_PID=$!
    
    sleep 3
    
    # Run performance tests
    run_test_suite "Load handling test" "cargo test --release -p task-tests --test integration_test test_load_handling -- --ignored --nocapture" true
    run_test_suite "Throughput test" "cargo test --release -p task-tests --test performance_test test_throughput_single_client -- --ignored --nocapture" true
    run_test_suite "Burst load test" "cargo test --release -p task-tests --test performance_test test_burst_load -- --ignored --nocapture" true
    run_test_suite "Concurrent clients test" "cargo test --release -p task-tests --test performance_test test_concurrent_clients -- --ignored --nocapture --test-threads=1" true
    
    # Cleanup
    kill $MANAGER_PID $WORKER_PID 2>/dev/null || true
}

# Function to run resilience tests
run_resilience_tests() {
    print_header "Running Resilience Tests"
    
    cd "$PROJECT_ROOT"
    
    # Restart services for resilience tests
    setup_test_environment
    
    run_test_suite "Task retry mechanism" "cargo test -p task-tests --test resilience_test test_task_retry_mechanism -- --nocapture"
    run_test_suite "Worker failure recovery" "cargo test -p task-tests --test resilience_test test_worker_failure_recovery -- --ignored --nocapture" true
    run_test_suite "NATS resilience" "cargo test -p task-tests --test resilience_test test_nats_connection_resilience -- --ignored --nocapture" true
    run_test_suite "Malformed requests" "cargo test -p task-tests --test resilience_test test_malformed_request_handling -- --nocapture"
    run_test_suite "Concurrent stress" "cargo test -p task-tests --test resilience_test test_concurrent_submission_stress -- --nocapture"
    run_test_suite "Graceful degradation" "cargo test -p task-tests --test resilience_test test_graceful_degradation -- --ignored --nocapture" true
}

# Function to run plugin tests
run_plugin_tests() {
    print_header "Running Plugin System Tests"
    
    cd "$PROJECT_ROOT"
    
    run_test_suite "Builtin plugins" "cargo test -p task-tests --test plugin_system_test test_builtin_plugins -- --nocapture"
    run_test_suite "Plugin registry" "cargo test -p task-tests --test plugin_system_test test_plugin_registry -- --nocapture"
    run_test_suite "Plugin loader" "cargo test -p task-tests --test plugin_system_test test_plugin_loader -- --nocapture"
    run_test_suite "Plugin execution" "cargo test -p task-tests --test plugin_system_test test_plugin_method_execution -- --nocapture"
    run_test_suite "Plugin isolation" "cargo test -p task-tests --test plugin_system_test test_plugin_isolation -- --nocapture"
}

# Function to generate test report
generate_report() {
    print_header "Test Report"
    
    local total_tests=$((PASSED_TESTS + FAILED_TESTS + SKIPPED_TESTS))
    local pass_rate=0
    if [ $total_tests -gt 0 ]; then
        pass_rate=$(awk "BEGIN {printf \"%.1f\", ($PASSED_TESTS / $total_tests) * 100}")
    fi
    
    echo -e "${CYAN}Total Tests Run: $total_tests${NC}"
    echo -e "${GREEN}Passed: $PASSED_TESTS${NC}"
    echo -e "${RED}Failed: $FAILED_TESTS${NC}"
    echo -e "${YELLOW}Skipped/Allowed Failures: $SKIPPED_TESTS${NC}"
    echo -e "${CYAN}Pass Rate: $pass_rate%${NC}"
    echo ""
    echo -e "Full log available at: ${BLUE}$LOG_FILE${NC}"
    
    # Save summary to file
    local summary_file="$PROJECT_ROOT/test-summary-$(date +%Y%m%d-%H%M%S).txt"
    {
        echo "Test Run Summary - $(date)"
        echo "=========================="
        echo "Total Tests: $total_tests"
        echo "Passed: $PASSED_TESTS"
        echo "Failed: $FAILED_TESTS"
        echo "Skipped: $SKIPPED_TESTS"
        echo "Pass Rate: $pass_rate%"
        echo ""
        echo "Log file: $LOG_FILE"
    } > "$summary_file"
    
    echo -e "Summary saved to: ${BLUE}$summary_file${NC}"
}

# Main execution
main() {
    echo -e "${CYAN}╔═══════════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${CYAN}║        Rust Task Scheduler - Comprehensive Test Suite             ║${NC}"
    echo -e "${CYAN}╚═══════════════════════════════════════════════════════════════════╝${NC}"
    
    # Start logging
    echo "Test run started at $(date)" > "$LOG_FILE"
    
    # Check prerequisites
    check_prerequisites
    
    # Build project
    build_project
    
    # Setup test environment
    setup_test_environment
    
    # Run all test suites
    run_unit_tests
    run_storage_tests
    run_integration_tests
    run_plugin_tests
    run_resilience_tests
    
    # Run performance tests last (they use release mode)
    run_performance_tests
    
    # Cleanup
    teardown_test_environment
    
    # Generate report
    generate_report
    
    # Exit with appropriate code
    if [ $FAILED_TESTS -eq 0 ]; then
        echo -e "${GREEN}All tests completed successfully!${NC}"
        exit 0
    else
        echo -e "${RED}Some tests failed. Please check the logs.${NC}"
        exit 1
    fi
}

# Handle interrupts
trap 'echo -e "\n${RED}Test run interrupted!${NC}"; teardown_test_environment; exit 1' INT TERM

# Run main function
main "$@"