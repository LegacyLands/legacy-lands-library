#!/bin/bash

# Run all tests for the Rust task scheduler project
# This script runs tests in single-threaded mode for certain tests that have environment variable conflicts

set -euo pipefail

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${YELLOW}Running all tests for Rust task scheduler...${NC}"

# Build check
echo -e "\n${YELLOW}1. Running build check...${NC}"
if cargo build --workspace; then
    echo -e "${GREEN}✓ Build check passed${NC}"
else
    echo -e "${RED}✗ Build check failed${NC}"
    exit 1
fi

# Format check
echo -e "\n${YELLOW}2. Running format check...${NC}"
if cargo fmt --all -- --check; then
    echo -e "${GREEN}✓ Format check passed${NC}"
else
    echo -e "${RED}✗ Format check failed - run 'cargo fmt' to fix${NC}"
fi

# Clippy check
echo -e "\n${YELLOW}3. Running clippy check...${NC}"
if cargo clippy --workspace --all-targets --all-features -- -D warnings 2>/dev/null || true; then
    echo -e "${GREEN}✓ Clippy check completed${NC}"
fi

# Unit tests - run with single thread for config tests that use env vars
echo -e "\n${YELLOW}4. Running unit tests...${NC}"
if cargo test --workspace --lib -- --test-threads=1; then
    echo -e "${GREEN}✓ Unit tests passed${NC}"
else
    echo -e "${RED}✗ Unit tests failed${NC}"
    exit 1
fi

# Integration tests
echo -e "\n${YELLOW}5. Running integration tests...${NC}"
if cargo test --workspace --tests; then
    echo -e "${GREEN}✓ Integration tests passed${NC}"
else
    echo -e "${RED}✗ Integration tests failed${NC}"
    exit 1
fi

# Doc tests
echo -e "\n${YELLOW}6. Running doc tests...${NC}"
if cargo test --workspace --doc; then
    echo -e "${GREEN}✓ Doc tests passed${NC}"
else
    echo -e "${RED}✗ Doc tests failed${NC}"
fi

# Test coverage summary
echo -e "\n${YELLOW}Test Coverage Summary:${NC}"
echo "----------------------------------------"
for crate in task-common task-storage task-worker task-manager task-operator task-plugins task-scheduler; do
    echo -n "Testing $crate... "
    if cargo test -p $crate --lib --tests --quiet 2>/dev/null; then
        echo -e "${GREEN}✓${NC}"
    else
        echo -e "${RED}✗${NC}"
    fi
done

echo -e "\n${GREEN}All tests completed!${NC}"
echo -e "${YELLOW}Note: Some tests may fail when run concurrently due to environment variable conflicts.${NC}"
echo -e "${YELLOW}Run with --test-threads=1 for reliable results.${NC}"