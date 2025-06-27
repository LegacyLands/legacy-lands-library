#!/bin/bash
# Setup Test Environment for Task Scheduler
# This script sets up all required services for testing

set -e

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}Task Scheduler Test Environment Setup${NC}"
echo -e "${BLUE}=====================================\${NC}"
echo

# Function to check if command exists
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

# Function to check if Docker container is running
container_running() {
    docker ps --format '{{.Names}}' | grep -q "^$1$"
}

# Check Docker
if ! command_exists docker; then
    echo -e "${RED}Error: Docker is not installed${NC}"
    echo "Please install Docker first: https://docs.docker.com/get-docker/"
    exit 1
fi

# Create docker network if not exists
if ! docker network ls | grep -q task-scheduler-net; then
    echo -e "${YELLOW}Creating Docker network...${NC}"
    docker network create task-scheduler-net
fi

# PostgreSQL Setup
echo -e "${YELLOW}Setting up PostgreSQL...${NC}"
if container_running "task-scheduler-postgres"; then
    echo -e "${GREEN}PostgreSQL is already running${NC}"
else
    docker run -d \
        --name task-scheduler-postgres \
        --network task-scheduler-net \
        -e POSTGRES_USER=postgres \
        -e POSTGRES_PASSWORD=postgres \
        -e POSTGRES_DB=task_scheduler \
        -p 5432:5432 \
        -v task-scheduler-postgres-data:/var/lib/postgresql/data \
        postgres:15-alpine
    
    echo "Waiting for PostgreSQL to start..."
    sleep 5
    
    # Create test database
    docker exec task-scheduler-postgres psql -U postgres -c "CREATE DATABASE task_scheduler_test;" || true
fi

# MongoDB Setup (optional)
echo -e "${YELLOW}Setting up MongoDB (optional)...${NC}"
if container_running "task-scheduler-mongodb"; then
    echo -e "${GREEN}MongoDB is already running${NC}"
else
    read -p "Do you want to setup MongoDB for testing? (y/N) " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        docker run -d \
            --name task-scheduler-mongodb \
            --network task-scheduler-net \
            -e MONGO_INITDB_ROOT_USERNAME=admin \
            -e MONGO_INITDB_ROOT_PASSWORD=admin \
            -p 27017:27017 \
            -v task-scheduler-mongodb-data:/data/db \
            mongo:7
        
        echo "Waiting for MongoDB to start..."
        sleep 5
    fi
fi

# NATS Setup
echo -e "${YELLOW}Setting up NATS...${NC}"
if container_running "task-scheduler-nats"; then
    echo -e "${GREEN}NATS is already running${NC}"
else
    docker run -d \
        --name task-scheduler-nats \
        --network task-scheduler-net \
        -p 4222:4222 \
        -p 8222:8222 \
        -p 6222:6222 \
        nats:2.10-alpine \
        -js -m 8222
    
    echo "Waiting for NATS to start..."
    sleep 3
fi

# Redis Setup (optional, for caching)
echo -e "${YELLOW}Setting up Redis (optional)...${NC}"
if container_running "task-scheduler-redis"; then
    echo -e "${GREEN}Redis is already running${NC}"
else
    read -p "Do you want to setup Redis for caching? (y/N) " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        docker run -d \
            --name task-scheduler-redis \
            --network task-scheduler-net \
            -p 6379:6379 \
            redis:7-alpine
    fi
fi

# Jaeger Setup (optional, for tracing)
echo -e "${YELLOW}Setting up Jaeger (optional)...${NC}"
if container_running "task-scheduler-jaeger"; then
    echo -e "${GREEN}Jaeger is already running${NC}"
else
    read -p "Do you want to setup Jaeger for distributed tracing? (y/N) " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        docker run -d \
            --name task-scheduler-jaeger \
            --network task-scheduler-net \
            -e COLLECTOR_ZIPKIN_HOST_PORT=:9411 \
            -p 5775:5775/udp \
            -p 6831:6831/udp \
            -p 6832:6832/udp \
            -p 5778:5778 \
            -p 16686:16686 \
            -p 14268:14268 \
            -p 14250:14250 \
            -p 9411:9411 \
            jaegertracing/all-in-one:latest
    fi
fi

# MinIO Setup (optional, for plugin storage)
echo -e "${YELLOW}Setting up MinIO (optional)...${NC}"
if container_running "task-scheduler-minio"; then
    echo -e "${GREEN}MinIO is already running${NC}"
else
    read -p "Do you want to setup MinIO for plugin storage? (y/N) " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        docker run -d \
            --name task-scheduler-minio \
            --network task-scheduler-net \
            -p 9000:9000 \
            -p 9001:9001 \
            -e MINIO_ROOT_USER=minioadmin \
            -e MINIO_ROOT_PASSWORD=minioadmin \
            -v task-scheduler-minio-data:/data \
            quay.io/minio/minio:latest \
            server /data --console-address ":9001"
        
        echo "Waiting for MinIO to start..."
        sleep 5
        
        # Create bucket for plugins
        docker exec task-scheduler-minio mc alias set local http://localhost:9000 minioadmin minioadmin
        docker exec task-scheduler-minio mc mb local/plugins || true
    fi
fi

# Display status
echo
echo -e "${GREEN}Test Environment Status:${NC}"
echo -e "${GREEN}========================${NC}"

# Check services
services=(
    "task-scheduler-postgres:PostgreSQL:5432"
    "task-scheduler-mongodb:MongoDB:27017"
    "task-scheduler-nats:NATS:4222"
    "task-scheduler-redis:Redis:6379"
    "task-scheduler-jaeger:Jaeger:16686"
    "task-scheduler-minio:MinIO:9001"
)

for service in "${services[@]}"; do
    IFS=':' read -r container name port <<< "$service"
    if container_running "$container"; then
        echo -e "${GREEN}✓${NC} $name is running on port $port"
    else
        echo -e "${YELLOW}○${NC} $name is not running"
    fi
done

# Export environment variables
echo
echo -e "${YELLOW}Environment Variables:${NC}"
echo "export TEST_POSTGRES_URL='postgres://postgres:postgres@localhost:5432/task_scheduler_test'"
echo "export TEST_MONGODB_URL='mongodb://admin:admin@localhost:27017'"
echo "export TEST_NATS_URL='nats://localhost:4222'"
echo "export TEST_REDIS_URL='redis://localhost:6379'"
echo

# Create environment file
cat > .env.test << EOF
# Test Environment Configuration
TEST_POSTGRES_URL=postgres://postgres:postgres@localhost:5432/task_scheduler_test
TEST_MONGODB_URL=mongodb://admin:admin@localhost:27017
TEST_MONGODB_DATABASE=task_scheduler_test
TEST_NATS_URL=nats://localhost:4222
TEST_REDIS_URL=redis://localhost:6379
JAEGER_AGENT_HOST=localhost
JAEGER_AGENT_PORT=6831
MINIO_ENDPOINT=localhost:9000
MINIO_ACCESS_KEY=minioadmin
MINIO_SECRET_KEY=minioadmin
EOF

echo -e "${GREEN}Environment file created: .env.test${NC}"
echo

# Docker Compose alternative
echo -e "${YELLOW}Creating docker-compose.yml for easy management...${NC}"
cat > docker-compose.test.yml << 'EOF'
version: '3.8'

services:
  postgres:
    image: postgres:15-alpine
    container_name: task-scheduler-postgres
    environment:
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
      POSTGRES_DB: task_scheduler
    ports:
      - "5432:5432"
    volumes:
      - postgres-data:/var/lib/postgresql/data
    networks:
      - task-scheduler-net
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres"]
      interval: 10s
      timeout: 5s
      retries: 5

  mongodb:
    image: mongo:7
    container_name: task-scheduler-mongodb
    environment:
      MONGO_INITDB_ROOT_USERNAME: admin
      MONGO_INITDB_ROOT_PASSWORD: admin
    ports:
      - "27017:27017"
    volumes:
      - mongodb-data:/data/db
    networks:
      - task-scheduler-net

  nats:
    image: nats:2.10-alpine
    container_name: task-scheduler-nats
    command: ["-js", "-m", "8222"]
    ports:
      - "4222:4222"
      - "8222:8222"
      - "6222:6222"
    networks:
      - task-scheduler-net

  redis:
    image: redis:7-alpine
    container_name: task-scheduler-redis
    ports:
      - "6379:6379"
    networks:
      - task-scheduler-net

  jaeger:
    image: jaegertracing/all-in-one:latest
    container_name: task-scheduler-jaeger
    environment:
      COLLECTOR_ZIPKIN_HOST_PORT: :9411
    ports:
      - "5775:5775/udp"
      - "6831:6831/udp"
      - "6832:6832/udp"
      - "5778:5778"
      - "16686:16686"
      - "14268:14268"
      - "14250:14250"
      - "9411:9411"
    networks:
      - task-scheduler-net

  minio:
    image: quay.io/minio/minio:latest
    container_name: task-scheduler-minio
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: minioadmin
      MINIO_ROOT_PASSWORD: minioadmin
    ports:
      - "9000:9000"
      - "9001:9001"
    volumes:
      - minio-data:/data
    networks:
      - task-scheduler-net

volumes:
  postgres-data:
  mongodb-data:
  minio-data:

networks:
  task-scheduler-net:
    external: true
EOF

echo -e "${GREEN}Docker Compose file created: docker-compose.test.yml${NC}"
echo
echo -e "${BLUE}Quick Commands:${NC}"
echo "  Start all services:  docker-compose -f docker-compose.test.yml up -d"
echo "  Stop all services:   docker-compose -f docker-compose.test.yml down"
echo "  View logs:           docker-compose -f docker-compose.test.yml logs -f"
echo "  Clean up:            docker-compose -f docker-compose.test.yml down -v"
echo
echo -e "${GREEN}Test environment setup complete!${NC}"
echo
echo "Next steps:"
echo "1. Source the environment variables: source .env.test"
echo "2. Run the test suite: ./scripts/complete-test-suite.sh"