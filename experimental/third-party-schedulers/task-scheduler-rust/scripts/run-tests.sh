#!/bin/bash
# Simple test runner for Rust Task Scheduler
# Usage: ./scripts/run-tests.sh [unit|integration|all]

set -e

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Get script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Test type
TEST_TYPE="${1:-all}"

echo -e "${BLUE}═══════════════════════════════════════════${NC}"
echo -e "${BLUE}    Rust Task Scheduler Test Runner${NC}"
echo -e "${BLUE}═══════════════════════════════════════════${NC}"

# Function to run unit tests
run_unit_tests() {
    echo -e "\n${YELLOW}Running unit tests...${NC}"
    cd "$PROJECT_ROOT"
    
    if cargo test --all --lib -- --test-threads=1; then
        echo -e "${GREEN}✓ Unit tests passed${NC}"
        return 0
    else
        echo -e "${RED}✗ Unit tests failed${NC}"
        return 1
    fi
}

# Function to run integration tests
run_integration_tests() {
    echo -e "\n${YELLOW}Running integration tests...${NC}"
    cd "$PROJECT_ROOT"
    
    # Check if Docker is running
    if ! docker info >/dev/null 2>&1; then
        echo -e "${RED}Docker is not running. Please start Docker first.${NC}"
        return 1
    fi
    
    # Start services
    echo -e "${YELLOW}Starting services...${NC}"
    cd "$PROJECT_ROOT/deploy/docker"
    docker compose up -d nats postgres redis
    
    # Wait for services
    echo -e "${YELLOW}Waiting for services to be ready...${NC}"
    sleep 10
    
    # Run integration tests
    cd "$PROJECT_ROOT"
    if cargo test --all --test '*' -- --test-threads=1 --nocapture; then
        echo -e "${GREEN}✓ Integration tests passed${NC}"
        RESULT=0
    else
        echo -e "${RED}✗ Integration tests failed${NC}"
        RESULT=1
    fi
    
    # Cleanup
    echo -e "${YELLOW}Stopping services...${NC}"
    cd "$PROJECT_ROOT/deploy/docker"
    docker compose down -v
    
    return $RESULT
}

# Main execution
case "$TEST_TYPE" in
    unit)
        run_unit_tests
        ;;
    integration)
        run_integration_tests
        ;;
    all)
        run_unit_tests && run_integration_tests
        ;;
    *)
        echo -e "${RED}Unknown test type: $TEST_TYPE${NC}"
        echo "Usage: $0 [unit|integration|all]"
        exit 1
        ;;
esac

EXIT_CODE=$?

if [ $EXIT_CODE -eq 0 ]; then
    echo -e "\n${GREEN}✅ All tests passed!${NC}"
else
    echo -e "\n${RED}❌ Some tests failed!${NC}"
fi

exit $EXIT_CODE