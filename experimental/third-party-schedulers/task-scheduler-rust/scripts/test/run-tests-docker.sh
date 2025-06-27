#!/bin/bash
# Run all tests with Docker services

set -e

echo "=== Running Tests with Docker Services ==="
echo ""

# Color codes
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Check if ports are occupied
check_ports() {
    local has_conflict=false
    
    echo -e "${YELLOW}Checking for port conflicts...${NC}"
    
    # Check PostgreSQL port 5432
    if nc -z localhost 5432 2>/dev/null; then
        if ! docker ps | grep -q test-postgres; then
            echo -e "${RED}✗ Port 5432 is occupied (PostgreSQL)${NC}"
            has_conflict=true
        fi
    fi
    
    # Check MongoDB port 27017  
    if nc -z localhost 27017 2>/dev/null; then
        if ! docker ps | grep -q test-mongodb; then
            echo -e "${RED}✗ Port 27017 is occupied (MongoDB)${NC}"
            has_conflict=true
        fi
    fi
    
    # Check Redis port 6379
    if nc -z localhost 6379 2>/dev/null; then
        if ! docker ps | grep -q test-redis; then
            echo -e "${RED}✗ Port 6379 is occupied (Redis)${NC}"
            has_conflict=true
        fi
    fi
    
    # Check NATS port 4222
    if nc -z localhost 4222 2>/dev/null; then
        if ! docker ps | grep -q test-nats; then
            echo -e "${RED}✗ Port 4222 is occupied (NATS)${NC}"
            has_conflict=true
        fi
    fi
    
    if [ "$has_conflict" = true ]; then
        echo ""
        echo -e "${YELLOW}To stop system services, run:${NC}"
        echo "  sudo systemctl stop mongod redis"
        echo ""
        echo -e "${YELLOW}Or run the helper script:${NC}"
        echo "  sudo ./scripts/stop-system-services.sh"
        echo ""
        return 1
    fi
    
    echo -e "${GREEN}✓ All ports are available${NC}"
    return 0
}

# Function to check if services are healthy
check_services() {
    echo -e "${YELLOW}Checking services health...${NC}"
    
    # Check NATS
    if curl -s http://localhost:8222/healthz > /dev/null 2>&1; then
        echo -e "${GREEN}✓ NATS is healthy${NC}"
    else
        echo -e "${RED}✗ NATS is not healthy${NC}"
        return 1
    fi
    
    # Check PostgreSQL
    if docker exec test-postgres psql -U postgres -c "SELECT 1" > /dev/null 2>&1; then
        echo -e "${GREEN}✓ PostgreSQL is healthy${NC}"
        # Try to create test database if it doesn't exist
        docker exec test-postgres psql -U postgres -c "CREATE DATABASE task_scheduler_test" 2>/dev/null || true
    else
        echo -e "${RED}✗ PostgreSQL is not healthy${NC}"
        return 1
    fi
    
    # Check MongoDB
    if docker exec test-mongodb mongosh --eval "db.adminCommand('ping')" --quiet > /dev/null 2>&1; then
        echo -e "${GREEN}✓ MongoDB is healthy${NC}"
    else
        echo -e "${RED}✗ MongoDB is not healthy${NC}"
        return 1
    fi
    
    # Check Redis
    if docker exec test-redis redis-cli ping > /dev/null 2>&1; then
        echo -e "${GREEN}✓ Redis is healthy${NC}"
    else
        echo -e "${RED}✗ Redis is not healthy${NC}"
        return 1
    fi
    
    return 0
}

# Check ports first
if ! check_ports; then
    exit 1
fi

echo ""

# Clean up any existing test containers
echo -e "${YELLOW}Cleaning up existing test containers...${NC}"
docker rm -f test-nats test-postgres test-mongodb test-redis 2>/dev/null || true

# Start all services with docker-compose
echo -e "${YELLOW}Starting Docker services...${NC}"
docker compose -f docker-compose.test.yml up -d

# Wait for services to be ready
echo -e "${YELLOW}Waiting for services to start...${NC}"
sleep 10

# Check if all services are healthy
if ! check_services; then
    echo -e "${RED}Services failed to start properly.${NC}"
    docker compose -f docker-compose.test.yml logs
    exit 1
fi

echo ""

# Set environment variables for tests
export TEST_POSTGRES_URL="postgres://postgres:password@localhost:5432/task_scheduler_test"
export TEST_MONGODB_URL="mongodb://localhost:27017"
export TEST_MONGODB_DATABASE="test_tasks"
export GRPC_ADDRESS="http://localhost:50051"
export NATS_URL="nats://localhost:4222"
# Don't set CI=1 so tests use localhost instead of docker service names

echo -e "${GREEN}Environment variables set${NC}"
echo ""

# Function to cleanup on exit
cleanup() {
    echo -e "${YELLOW}Cleaning up...${NC}"
    docker compose -f docker-compose.test.yml down
}
trap cleanup EXIT

# Run storage tests
echo -e "${YELLOW}Running storage tests...${NC}"
cargo test -p task-storage --all-features -- --test-threads=1

echo ""

# Run unit tests
echo -e "${YELLOW}Running unit tests...${NC}"
cargo test --all --lib -- --test-threads=1

echo ""

# Build binaries
echo -e "${YELLOW}Building task-manager and task-worker...${NC}"
cargo build --bin task-manager --bin task-worker

# Start task-manager
echo -e "${YELLOW}Starting task-manager...${NC}"
GRPC_ADDRESS=0.0.0.0:50051 METRICS_ADDRESS=0.0.0.0:9091 NATS_URL=nats://localhost:4222 \
    ./target/debug/task-manager &
TASK_MANAGER_PID=$!
sleep 5

# Start task-worker
echo -e "${YELLOW}Starting task-worker...${NC}"
MANAGER_ADDRESS=localhost:50051 NATS_URL=nats://localhost:4222 WORKER_ID=test-worker-1 \
    ./target/debug/task-worker &
TASK_WORKER_PID=$!
sleep 5

# Update cleanup function
cleanup() {
    echo -e "${YELLOW}Cleaning up...${NC}"
    kill $TASK_MANAGER_PID $TASK_WORKER_PID 2>/dev/null || true
    docker compose -f docker-compose.test.yml down
}

# Run integration tests
echo -e "${YELLOW}Running integration tests...${NC}"
cargo test --all --test integration_test -- --test-threads=1

echo ""

# Run ignored tests
echo -e "${YELLOW}Running ignored tests...${NC}"
cargo test --all -- --ignored --test-threads=1

echo ""

echo -e "${GREEN}=== All tests completed! ===${NC}"