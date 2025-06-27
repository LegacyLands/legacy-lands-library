#!/bin/bash
# Task Scheduler Testing Guide
# This script provides an interactive guide for running different test scenarios

set -e

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m'

# Function to print menu
print_menu() {
    clear
    echo -e "${BLUE}================================${NC}"
    echo -e "${BLUE}Task Scheduler Test Suite Guide${NC}"
    echo -e "${BLUE}================================${NC}"
    echo
    echo "1. Quick Test (Unit tests only)"
    echo "2. Standard Test (Unit + Integration)"
    echo "3. Full Test (All tests including optional)"
    echo "4. Storage Tests (PostgreSQL/MongoDB)"
    echo "5. Performance Tests"
    echo "6. End-to-End Tests"
    echo "7. Kubernetes Tests"
    echo "8. Setup Test Environment"
    echo "9. View Test Coverage"
    echo "10. Run Specific Crate Tests"
    echo "11. Exit"
    echo
}

# Function to run tests
run_tests() {
    local test_type=$1
    echo -e "${YELLOW}Running $test_type tests...${NC}"
    
    case $test_type in
        "quick")
            cargo test --lib --all
            ;;
        "standard")
            cargo test --all
            ;;
        "full")
            ./scripts/complete-test-suite.sh
            ;;
        "storage")
            echo "Testing PostgreSQL storage..."
            cargo test -p task-storage --features postgres -- --test-threads=1
            echo
            echo "Testing MongoDB storage..."
            cargo test -p task-storage --features mongodb -- --test-threads=1
            ;;
        "performance")
            echo "Running benchmarks..."
            cargo bench
            echo
            echo "Running load tests..."
            cargo run -p task-load-test -- --tasks 1000 --workers 10 --duration 60
            ;;
        "e2e")
            ./scripts/run-e2e-test.sh
            ;;
        "k8s")
            echo "Deploying to test Kubernetes cluster..."
            ./scripts/deploy-to-k8s.sh test
            echo
            echo "Running Kubernetes integration tests..."
            cargo test -p task-operator --test k8s_integration_test -- --nocapture
            ;;
        *)
            echo -e "${RED}Unknown test type: $test_type${NC}"
            ;;
    esac
}

# Function to setup environment
setup_environment() {
    echo -e "${YELLOW}Setting up test environment...${NC}"
    ./scripts/setup-test-environment.sh
}

# Function to show coverage
show_coverage() {
    echo -e "${YELLOW}Generating test coverage report...${NC}"
    
    # Install tarpaulin if not installed
    if ! command -v cargo-tarpaulin &> /dev/null; then
        echo "Installing cargo-tarpaulin..."
        cargo install cargo-tarpaulin
    fi
    
    # Generate coverage
    cargo tarpaulin --all-features --workspace --out Html --output-dir target/coverage
    echo
    echo -e "${GREEN}Coverage report generated at: target/coverage/index.html${NC}"
    
    # Try to open in browser
    if command -v xdg-open &> /dev/null; then
        xdg-open target/coverage/index.html
    elif command -v open &> /dev/null; then
        open target/coverage/index.html
    fi
}

# Function to test specific crate
test_specific_crate() {
    echo "Available crates:"
    echo "1. task-common"
    echo "2. task-storage"
    echo "3. task-worker"
    echo "4. task-manager"
    echo "5. task-operator"
    echo "6. task-plugins"
    echo "7. task-scheduler"
    echo
    read -p "Select crate number: " crate_num
    
    case $crate_num in
        1) crate="task-common" ;;
        2) crate="task-storage" ;;
        3) crate="task-worker" ;;
        4) crate="task-manager" ;;
        5) crate="task-operator" ;;
        6) crate="task-plugins" ;;
        7) crate="task-scheduler" ;;
        *) echo -e "${RED}Invalid selection${NC}"; return ;;
    esac
    
    echo -e "${YELLOW}Testing $crate...${NC}"
    cargo test -p $crate --all-features -- --nocapture
}

# Main loop
while true; do
    print_menu
    read -p "Select option (1-11): " choice
    
    case $choice in
        1) run_tests "quick" ;;
        2) run_tests "standard" ;;
        3) run_tests "full" ;;
        4) run_tests "storage" ;;
        5) run_tests "performance" ;;
        6) run_tests "e2e" ;;
        7) run_tests "k8s" ;;
        8) setup_environment ;;
        9) show_coverage ;;
        10) test_specific_crate ;;
        11) echo "Exiting..."; exit 0 ;;
        *) echo -e "${RED}Invalid option${NC}" ;;
    esac
    
    echo
    read -p "Press Enter to continue..."
done