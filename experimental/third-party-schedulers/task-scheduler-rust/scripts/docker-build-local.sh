#!/bin/bash

# Build script for Docker images using locally built binaries
# This avoids building Rust code inside Docker and uses China mirrors

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

echo "=== Docker Build Script (Optimized) ==="
echo "This script intelligently skips unnecessary rebuilds"

echo "=== Building Rust binaries locally ==="

# Check if binaries already exist
NEED_BUILD=false
for binary in task-manager task-worker task-operator; do
    if [ ! -f "target/release/$binary" ]; then
        echo "Binary $binary not found, need to build"
        NEED_BUILD=true
        break
    fi
done

if [ "$NEED_BUILD" = true ]; then
    echo "Building all binaries..."
    cargo build --release
else
    echo "Checking if binaries are up to date..."
    # Find newest source file timestamp
    NEWEST_SOURCE=$(find crates -name "*.rs" -type f -exec stat -c %Y {} \; | sort -n | tail -1)
    # Find oldest binary timestamp
    OLDEST_BINARY=$(stat -c %Y target/release/task-manager target/release/task-worker target/release/task-operator 2>/dev/null | sort -n | head -1)
    
    if [ -z "$OLDEST_BINARY" ] || [ "$NEWEST_SOURCE" -gt "$OLDEST_BINARY" ]; then
        echo "Source files are newer than binaries, rebuilding..."
        cargo build --release
    else
        echo "✅ Binaries are up to date, skipping build!"
    fi
fi

# Verify binaries exist
if [ ! -f "target/release/task-manager" ]; then
    echo "Error: task-manager binary not found!"
    exit 1
fi

if [ ! -f "target/release/task-worker" ]; then
    echo "Error: task-worker binary not found!"
    exit 1
fi

echo "=== Building Docker images ==="

# Create Dockerfile.manager.local if it doesn't exist or is older than this script
if [ ! -f deploy/docker/Dockerfile.manager.local ] || [ $0 -nt deploy/docker/Dockerfile.manager.local ]; then
    echo "Creating Dockerfile.manager.local..."
    cat > deploy/docker/Dockerfile.manager.local <<'EOF'
# Runtime stage only - assumes binary is already built locally
FROM debian:bookworm-slim

# Configure apt sources for China
RUN sed -i 's/deb.debian.org/mirrors.ustc.edu.cn/g' /etc/apt/sources.list.d/debian.sources

# Install runtime dependencies
RUN apt-get update && apt-get install -y \
    ca-certificates \
    libssl3 \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Create non-root user
RUN useradd -m -u 1000 -s /bin/bash appuser

WORKDIR /app

# Copy pre-built binary from local build
COPY target/release/task-manager /app/task-manager

# Copy default configuration
COPY crates/task-manager/config.example.toml /app/config.toml

# Change ownership
RUN chown -R appuser:appuser /app

USER appuser

# Expose ports
EXPOSE 50051 9000

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD ["/bin/sh", "-c", "curl -f http://localhost:9000/health || exit 1"]

# Run the application
ENTRYPOINT ["/app/task-manager"]
CMD ["--config", "/app/config.toml"]
EOF
fi

# Create Dockerfile.worker.local if it doesn't exist or is older than this script
if [ ! -f deploy/docker/Dockerfile.worker.local ] || [ $0 -nt deploy/docker/Dockerfile.worker.local ]; then
    echo "Creating Dockerfile.worker.local..."
    cat > deploy/docker/Dockerfile.worker.local <<'EOF'
# Runtime stage only - assumes binary is already built locally
FROM debian:bookworm-slim

# Configure apt sources for China
RUN sed -i 's/deb.debian.org/mirrors.ustc.edu.cn/g' /etc/apt/sources.list.d/debian.sources

# Install runtime dependencies
RUN apt-get update && apt-get install -y \
    ca-certificates \
    libssl3 \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Create non-root user
RUN useradd -m -u 1000 -s /bin/bash appuser

WORKDIR /app

# Copy pre-built binary from local build
COPY target/release/task-worker /app/task-worker

# Copy default configuration
COPY crates/task-worker/config.example.toml /app/config.toml

# Change ownership
RUN chown -R appuser:appuser /app

USER appuser

# Expose ports
EXPOSE 50052 9001

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD ["/bin/sh", "-c", "curl -f http://localhost:9001/health || exit 1"]

# Run the application
ENTRYPOINT ["/app/task-worker"]
CMD ["--config", "/app/config.toml"]
EOF
fi

# Create Dockerfile.operator.local if it doesn't exist or is older than this script
if [ ! -f deploy/docker/Dockerfile.operator.local ] || [ $0 -nt deploy/docker/Dockerfile.operator.local ]; then
    echo "Creating Dockerfile.operator.local..."
    cat > deploy/docker/Dockerfile.operator.local <<'EOF'
# Runtime stage only - assumes binary is already built locally
FROM debian:bookworm-slim

# Configure apt sources for China
RUN sed -i 's/deb.debian.org/mirrors.ustc.edu.cn/g' /etc/apt/sources.list.d/debian.sources

# Install runtime dependencies
RUN apt-get update && apt-get install -y \
    ca-certificates \
    libssl3 \
    && rm -rf /var/lib/apt/lists/*

# Create non-root user
RUN useradd -m -u 1000 -s /bin/bash appuser

WORKDIR /app

# Copy pre-built binary from local build
COPY target/release/task-operator /app/task-operator

# Change ownership
RUN chown -R appuser:appuser /app

USER appuser

# Run the application
ENTRYPOINT ["/app/task-operator"]
EOF
fi

# Parse command line arguments
NO_CACHE=""
if [ "$1" = "--no-cache" ]; then
    NO_CACHE="--no-cache"
    echo "Building with --no-cache flag"
fi

# Build Docker images
echo ""
echo "=== Building Docker images ==="
echo "Building task-manager image..."
docker build $NO_CACHE -f deploy/docker/Dockerfile.manager.local -t task-manager:latest .

echo "Building task-worker image..."
docker build $NO_CACHE -f deploy/docker/Dockerfile.worker.local -t task-worker:latest .

echo "Building task-operator image..."
docker build $NO_CACHE -f deploy/docker/Dockerfile.operator.local -t task-operator:latest .

echo ""
echo "=== Docker images built successfully ==="
docker images | grep -E "task-(manager|worker|operator)" | head -3

echo ""
echo "To run with docker-compose:"
echo "  docker-compose -f deploy/docker/docker-compose.yaml up -d"
echo ""
echo "To push to registry:"
echo "  docker tag task-manager:latest your-registry/task-manager:latest"
echo "  docker push your-registry/task-manager:latest"
echo ""
echo "💡 Performance tips:"
echo "  - Binaries are only rebuilt if source files changed"
echo "  - Dockerfiles are only regenerated if this script changed"
echo "  - Docker uses cache layers by default (use --no-cache to force rebuild)"