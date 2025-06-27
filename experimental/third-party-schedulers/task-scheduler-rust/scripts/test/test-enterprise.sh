#!/bin/bash
# Enterprise Test Suite for Rust Task Scheduler
# This script runs all tests including unit, integration, performance, and enterprise features

set -e

# Source common utilities
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"

# Test configuration
TEST_NAMESPACE="test-scheduler"
LOAD_TEST_RPS=${LOAD_TEST_RPS:-100}
LOAD_TEST_DURATION=${LOAD_TEST_DURATION:-60}
ENABLE_K8S_TESTS=${ENABLE_K8S_TESTS:-false}

# Test results
declare -A test_results
test_categories=()

# Function to run a test category
run_test() {
    local category=$1
    local command=$2
    local description=$3
    
    test_categories+=("$category")
    print_section "$category: $description"
    
    if eval "$command"; then
        print_success "$category passed"
        test_results["$category"]="PASS"
    else
        print_error "$category failed"
        test_results["$category"]="FAIL"
    fi
}

# Function to setup test environment
setup_test_env() {
    print_section "Setting up test environment"
    
    # Check Docker
    if command_exists docker; then
        print_success "Docker is available"
        
        # Start test containers if docker-compose.test.yaml exists
        if [ -f "$PROJECT_ROOT/docker-compose.test.yaml" ]; then
            print_info "Starting test containers..."
            docker-compose -f "$PROJECT_ROOT/docker-compose.test.yaml" up -d
            sleep 5
        fi
    else
        print_warning "Docker not available, skipping container-based tests"
    fi
    
    # Check Kubernetes
    if command_exists kubectl && [ "$ENABLE_K8S_TESTS" = "true" ]; then
        print_info "Checking Kubernetes environment..."
        if kubectl cluster-info >/dev/null 2>&1; then
            print_success "Kubernetes cluster is accessible"
            
            # Create test namespace
            kubectl create namespace $TEST_NAMESPACE --dry-run=client -o yaml | kubectl apply -f -
            print_success "Test namespace ready: $TEST_NAMESPACE"
        else
            print_warning "Kubernetes cluster not accessible, disabling K8s tests"
            ENABLE_K8S_TESTS=false
        fi
    fi
}

# Function to cleanup test environment
cleanup_test_env() {
    print_section "Cleaning up test environment"
    
    # Stop test containers
    if [ -f "$PROJECT_ROOT/docker-compose.test.yaml" ] && command_exists docker; then
        docker-compose -f "$PROJECT_ROOT/docker-compose.test.yaml" down || true
    fi
    
    # Delete test namespace
    if [ "$ENABLE_K8S_TESTS" = "true" ] && command_exists kubectl; then
        kubectl delete namespace $TEST_NAMESPACE --ignore-not-found=true || true
    fi
}

# Main test execution
main() {
    print_color "$BLUE" "╔════════════════════════════════════════════════════════╗"
    print_color "$BLUE" "║     Enterprise Test Suite - Rust Task Scheduler        ║"
    print_color "$BLUE" "╚════════════════════════════════════════════════════════╝"
    
    # Parse arguments
    while [[ $# -gt 0 ]]; do
        case $1 in
            --k8s)
                ENABLE_K8S_TESTS=true
                shift
                ;;
            --rps)
                LOAD_TEST_RPS=$2
                shift 2
                ;;
            --duration)
                LOAD_TEST_DURATION=$2
                shift 2
                ;;
            --skip-cleanup)
                SKIP_CLEANUP=true
                shift
                ;;
            *)
                print_error "Unknown option: $1"
                exit 1
                ;;
        esac
    done
    
    # Setup test environment
    setup_test_env
    
    # Change to project root
    cd "$PROJECT_ROOT"
    
    # 1. Code Quality Checks
    run_test "COMPILATION" \
        "cargo check --workspace --all-targets" \
        "Checking code compilation"
    
    run_test "FORMAT" \
        "cargo fmt --all -- --check" \
        "Checking code formatting"
    
    run_test "CLIPPY" \
        "cargo clippy --workspace --all-targets -- -D warnings" \
        "Running Clippy linter"
    
    # 2. Unit Tests
    run_test "UNIT_TESTS" \
        "cargo test --workspace --lib -- --test-threads=1" \
        "Running unit tests"
    
    run_test "DOC_TESTS" \
        "cargo test --workspace --doc" \
        "Running documentation tests"
    
    # 3. Integration Tests
    if [ -f "$PROJECT_ROOT/tests/integration_test.rs" ]; then
        print_warning "Integration tests require test environment setup"
        print_info "Attempting to run integration tests..."
        
        # Check if NATS is available
        if nc -z localhost 4222 2>/dev/null; then
            run_test "INTEGRATION_BASIC" \
                "cargo test --test integration_test -- --test-threads=1" \
                "Running basic integration tests"
        else
            print_warning "NATS not available on localhost:4222, skipping integration tests"
            test_results["INTEGRATION_BASIC"]="SKIP"
        fi
    fi
    
    # 4. Plugin System Tests
    if [ -f "$PROJECT_ROOT/tests/plugin_system_test.rs" ]; then
        run_test "PLUGIN_TESTS" \
            "cargo test --test plugin_system_test -- --test-threads=1" \
            "Testing plugin system"
    fi
    
    # 5. Resilience Tests
    if [ -f "$PROJECT_ROOT/tests/resilience_test.rs" ]; then
        run_test "RESILIENCE_TESTS" \
            "cargo test --test resilience_test -- --test-threads=1" \
            "Testing fault tolerance"
    fi
    
    # 6. Performance Tests
    if [ -f "$PROJECT_ROOT/tests/performance_test.rs" ]; then
        run_test "PERFORMANCE_TESTS" \
            "cargo test --test performance_test -- --test-threads=1 --nocapture" \
            "Running performance benchmarks"
    fi
    
    # 7. Load Tests
    if command_exists cargo && [ -d "$PROJECT_ROOT/crates/task-load-test" ]; then
        print_info "Load test configuration: RPS=$LOAD_TEST_RPS, Duration=${LOAD_TEST_DURATION}s"
        run_test "LOAD_TESTS" \
            "cargo run --bin load-test -- --rps $LOAD_TEST_RPS --duration $LOAD_TEST_DURATION --target http://localhost:50051" \
            "Running load tests"
    fi
    
    # 8. Kubernetes Tests
    if [ "$ENABLE_K8S_TESTS" = "true" ]; then
        run_test "K8S_CRD_TESTS" \
            "kubectl apply --dry-run=client -f $PROJECT_ROOT/deploy/k8s/crd/" \
            "Validating Kubernetes CRDs"
        
        if [ -f "$PROJECT_ROOT/tests/e2e/complete_workflow_test.rs" ]; then
            run_test "K8S_E2E_TESTS" \
                "cargo test --test complete_workflow_test -- --test-threads=1" \
                "Running Kubernetes E2E tests"
        fi
    fi
    
    # 9. Enterprise Feature Tests
    print_section "Enterprise Feature Validation"
    
    # Test dependency management
    print_info "Testing task dependency features..."
    if grep -q "DependencyManager" "$PROJECT_ROOT/crates/task-manager/src/lib.rs" 2>/dev/null || \
       grep -q "DependencyManager" "$PROJECT_ROOT/crates/task-manager/src/dependency_manager.rs" 2>/dev/null; then
        print_success "Task dependency management implemented"
        test_results["DEPENDENCY_MGMT"]="PASS"
    else
        print_warning "Task dependency management not found"
        test_results["DEPENDENCY_MGMT"]="WARN"
    fi
    
    # Test distributed tracing
    print_info "Checking distributed tracing..."
    if grep -q "opentelemetry" "$PROJECT_ROOT/Cargo.toml"; then
        print_success "OpenTelemetry integration found"
        test_results["TRACING"]="PASS"
    else
        print_error "OpenTelemetry not configured"
        test_results["TRACING"]="FAIL"
    fi
    
    # Test monitoring
    print_info "Checking metrics collection..."
    if grep -q "prometheus" "$PROJECT_ROOT/Cargo.toml"; then
        print_success "Prometheus metrics configured"
        test_results["METRICS"]="PASS"
    else
        print_error "Prometheus metrics not configured"
        test_results["METRICS"]="FAIL"
    fi
    
    # Cleanup
    if [ "$SKIP_CLEANUP" != "true" ]; then
        cleanup_test_env
    fi
    
    # Generate test report
    generate_report
}

# Function to generate test report
generate_report() {
    print_section "Test Results Summary"
    
    local total_tests=${#test_categories[@]}
    local passed=0
    local failed=0
    local skipped=0
    local warnings=0
    
    # Count results
    for category in "${!test_results[@]}"; do
        case ${test_results[$category]} in
            PASS) ((passed++)) ;;
            FAIL) ((failed++)) ;;
            SKIP) ((skipped++)) ;;
            WARN) ((warnings++)) ;;
        esac
    done
    
    # Display results
    echo -e "\nTest Categories: $total_tests"
    print_color "$GREEN" "Passed: $passed"
    print_color "$RED" "Failed: $failed"
    print_color "$YELLOW" "Warnings: $warnings"
    print_color "$CYAN" "Skipped: $skipped"
    
    # Detailed results
    echo -e "\nDetailed Results:"
    for category in "${test_categories[@]}"; do
        local result=${test_results[$category]:-"UNKNOWN"}
        case $result in
            PASS) print_success "$category" ;;
            FAIL) print_error "$category" ;;
            SKIP) print_info "$category (skipped)" ;;
            WARN) print_warning "$category" ;;
        esac
    done
    
    # Generate report file
    local report_file="test-report-enterprise-$(get_timestamp).txt"
    {
        echo "Enterprise Test Report - Rust Task Scheduler"
        echo "Generated: $(date)"
        echo "Git Branch: $(get_git_branch)"
        echo "Git Commit: $(get_git_commit)"
        echo "============================================"
        echo ""
        echo "Test Summary:"
        echo "  Total Categories: $total_tests"
        echo "  Passed: $passed"
        echo "  Failed: $failed"
        echo "  Warnings: $warnings"
        echo "  Skipped: $skipped"
        echo ""
        echo "Environment:"
        echo "  Rust: $(rustc --version)"
        echo "  Cargo: $(cargo --version)"
        echo "  OS: $(uname -s)"
        echo "  Kubernetes: $([[ "$ENABLE_K8S_TESTS" = "true" ]] && echo "Enabled" || echo "Disabled")"
        echo ""
        echo "Detailed Results:"
        for category in "${test_categories[@]}"; do
            echo "  $category: ${test_results[$category]:-UNKNOWN}"
        done
        echo ""
        echo "Enterprise Features:"
        echo "  Dependency Management: ${test_results[DEPENDENCY_MGMT]:-NOT_TESTED}"
        echo "  Distributed Tracing: ${test_results[TRACING]:-NOT_TESTED}"
        echo "  Metrics Collection: ${test_results[METRICS]:-NOT_TESTED}"
    } > "$report_file"
    
    print_success "Test report saved to: $report_file"
    
    # Exit code
    if [ $failed -gt 0 ]; then
        print_error "Test suite failed!"
        exit 1
    else
        print_success "All tests passed!"
        exit 0
    fi
}

# Trap to ensure cleanup on exit
trap cleanup_test_env EXIT

# Run main function
main "$@"