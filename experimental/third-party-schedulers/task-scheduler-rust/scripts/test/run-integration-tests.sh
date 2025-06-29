#!/bin/bash
# Quick script to run integration tests
# This is optimized for development and debugging

set -e

# Color codes
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Script directory
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
UTILS_DIR="$SCRIPT_DIR/../utils"

echo -e "${BLUE}=== Running Integration Tests ===${NC}"

# Check if services are needed
if [ "$1" != "--no-services" ]; then
    echo -e "${YELLOW}Starting services...${NC}"
    "$UTILS_DIR/manage-services.sh" start
    
    # Wait for services to be ready
    sleep 5
fi

# Build in debug mode for faster compilation
echo -e "${YELLOW}Building project...${NC}"
cargo build --bin task-manager --bin task-worker

# Set environment variables
export GRPC_ADDRESS="http://localhost:50052"
export NATS_URL="nats://localhost:4222"
export TEST_POSTGRES_URL="postgres://postgres:password@localhost:5432/task_scheduler_test"

# Run integration tests
echo -e "${YELLOW}Running integration tests...${NC}"
cargo test -p task-tests --test integration_test -- --test-threads=1 --nocapture

# Check exit code
if [ $? -eq 0 ]; then
    echo -e "${GREEN}✅ All integration tests passed!${NC}"
else
    echo -e "${RED}❌ Some tests failed${NC}"
    exit 1
fi

# Optionally run ignored tests
if [ "$1" == "--with-ignored" ]; then
    echo -e "${YELLOW}Running ignored tests...${NC}"
    cargo test -p task-tests --test integration_test -- --ignored --test-threads=1 --nocapture
fi

# Cleanup if requested
if [ "$1" != "--keep-services" ] && [ "$2" != "--keep-services" ]; then
    echo -e "${YELLOW}Stopping services...${NC}"
    "$UTILS_DIR/manage-services.sh" stop
fi

echo -e "${BLUE}=== Integration Tests Complete ===${NC}"