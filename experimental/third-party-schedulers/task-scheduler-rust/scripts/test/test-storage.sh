#!/bin/bash
# Test script for task-storage crate

set -e

echo "Running task-storage tests..."
echo ""

# Color codes
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Test PostgreSQL if configured
if [ -n "$TEST_POSTGRES_URL" ]; then
    echo -e "${GREEN}PostgreSQL test database configured:${NC} $TEST_POSTGRES_URL"
    echo "Running PostgreSQL tests..."
    cargo test -p task-storage --features postgres -- --test-threads=1
else
    echo -e "${YELLOW}PostgreSQL tests skipped.${NC} Set TEST_POSTGRES_URL to enable."
    echo "Example: export TEST_POSTGRES_URL=postgres://user:pass@localhost/test_db"
fi

echo ""

# Test MongoDB if configured
if [ -n "$TEST_MONGODB_URL" ]; then
    echo -e "${GREEN}MongoDB test database configured:${NC} $TEST_MONGODB_URL"
    echo "Running MongoDB tests..."
    cargo test -p task-storage --features mongodb -- --test-threads=1
else
    echo -e "${YELLOW}MongoDB tests skipped.${NC} Set TEST_MONGODB_URL to enable."
    echo "Example: export TEST_MONGODB_URL=mongodb://localhost:27017"
    echo "         export TEST_MONGODB_DATABASE=test_tasks"
fi

echo ""

# Run all tests if both are configured
if [ -n "$TEST_POSTGRES_URL" ] && [ -n "$TEST_MONGODB_URL" ]; then
    echo -e "${GREEN}Running tests with all storage backends...${NC}"
    cargo test -p task-storage --features all-stores -- --test-threads=1
fi

echo ""
echo "Test run complete!"

# Provide setup instructions if no databases are configured
if [ -z "$TEST_POSTGRES_URL" ] && [ -z "$TEST_MONGODB_URL" ]; then
    echo ""
    echo -e "${RED}No test databases configured!${NC}"
    echo ""
    echo "To run tests, you need to set up at least one test database:"
    echo ""
    echo "For PostgreSQL:"
    echo "  1. Start PostgreSQL (e.g., using Docker):"
    echo "     docker run -d --name test-postgres -e POSTGRES_PASSWORD=password -p 5432:5432 postgres:15"
    echo "  2. Export the connection URL:"
    echo "     export TEST_POSTGRES_URL='postgres://postgres:password@localhost:5432/postgres'"
    echo ""
    echo "For MongoDB:"
    echo "  1. Start MongoDB (e.g., using Docker):"
    echo "     docker run -d --name test-mongo -p 27017:27017 mongo:7"
    echo "  2. Export the connection URL:"
    echo "     export TEST_MONGODB_URL='mongodb://localhost:27017'"
    echo "     export TEST_MONGODB_DATABASE='test_tasks'"
fi