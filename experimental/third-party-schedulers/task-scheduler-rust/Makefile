.PHONY: help build test clean docker-up docker-down k8s-setup k8s-deploy k8s-clean dev-setup

# Default target
help:
	@echo "Task Scheduler Development Commands:"
	@echo "  make dev-setup      - Setup development environment"
	@echo "  make build          - Build all crates"
	@echo "  make test           - Run all tests"
	@echo "  make clean          - Clean build artifacts"
	@echo "  make docker-up      - Start local Docker environment"
	@echo "  make docker-down    - Stop local Docker environment"
	@echo "  make k8s-setup      - Setup Kubernetes cluster (minikube)"
	@echo "  make k8s-deploy     - Deploy to Kubernetes"
	@echo "  make k8s-clean      - Clean Kubernetes resources"
	@echo "  make fmt            - Format code"
	@echo "  make clippy         - Run clippy linter"

# Development setup
dev-setup:
	@echo "Setting up development environment..."
	@command -v rustc >/dev/null 2>&1 || { echo "Rust is not installed. Please install from https://rustup.rs/"; exit 1; }
	@command -v cargo >/dev/null 2>&1 || { echo "Cargo is not installed."; exit 1; }
	@command -v docker >/dev/null 2>&1 || { echo "Docker is not installed."; exit 1; }
	@command -v kubectl >/dev/null 2>&1 || { echo "kubectl is not installed."; exit 1; }
	@echo "Installing cargo tools..."
	@cargo install cargo-watch cargo-expand cargo-audit || true
	@echo "Development environment ready!"

# Build commands
build:
	@echo "Building all crates..."
	@cargo build --workspace

build-release:
	@echo "Building release binaries..."
	@cargo build --workspace --release

# Test commands
test:
	@echo "Running tests..."
	@cargo test --workspace

test-unit:
	@echo "Running unit tests..."
	@cargo test --workspace --lib --all-features

test-integration:
	@echo "Running integration tests..."
	@cargo test --workspace --test '*' -- --test-threads=1

test-all: setup-test-env
	@echo "Running complete test suite..."
	@chmod +x scripts/*.sh
	@./scripts/complete-test-suite.sh

setup-test-env:
	@echo "Setting up test environment..."
	@chmod +x scripts/*.sh
	@./scripts/setup-test-environment.sh

run-e2e-test: setup-test-env
	@echo "Running end-to-end tests..."
	@./scripts/run-e2e-test.sh

# Code quality
fmt:
	@echo "Formatting code..."
	@cargo fmt --all

clippy:
	@echo "Running clippy..."
	@cargo clippy --workspace -- -D warnings

audit:
	@echo "Auditing dependencies..."
	@cargo audit

# Clean
clean:
	@echo "Cleaning build artifacts..."
	@cargo clean
	@rm -rf target/

# Docker commands
docker-up:
	@echo "Starting Docker services..."
	@docker-compose -f deploy/docker/docker-compose.yaml up -d
	@echo "Waiting for services to be ready..."
	@sleep 10
	@docker-compose -f deploy/docker/docker-compose.yaml ps

docker-down:
	@echo "Stopping Docker services..."
	@docker-compose -f deploy/docker/docker-compose.yaml down

docker-logs:
	@docker-compose -f deploy/docker/docker-compose.yaml logs -f

# Kubernetes commands
k8s-setup:
	@echo "Setting up Kubernetes cluster..."
	@command -v minikube >/dev/null 2>&1 || { echo "minikube is not installed."; exit 1; }
	@minikube status >/dev/null 2>&1 || minikube start --cpus=4 --memory=8192
	@echo "Enabling necessary addons..."
	@minikube addons enable metrics-server
	@minikube addons enable ingress
	@echo "Kubernetes cluster ready!"

k8s-deploy: k8s-setup
	@echo "Deploying to Kubernetes..."
	@kubectl apply -f deploy/k8s/services/namespace.yaml
	@kubectl apply -f deploy/k8s/crd/
	@kubectl apply -f deploy/k8s/rbac/
	@kubectl apply -f deploy/k8s/services/
	@echo "Waiting for deployments to be ready..."
	@kubectl -n task-scheduler wait --for=condition=ready pod -l app=nats --timeout=300s || true
	@echo "Deployment complete!"

k8s-clean:
	@echo "Cleaning Kubernetes resources..."
	@kubectl delete -f deploy/k8s/services/ --ignore-not-found=true
	@kubectl delete -f deploy/k8s/rbac/ --ignore-not-found=true
	@kubectl delete -f deploy/k8s/crd/ --ignore-not-found=true
	@kubectl delete namespace task-scheduler --ignore-not-found=true

k8s-status:
	@echo "Kubernetes deployment status:"
	@kubectl -n task-scheduler get all

# Development shortcuts
dev: docker-up
	@echo "Starting development environment..."
	@cargo watch -x 'run --bin task-manager'

run-manager:
	@cargo run --bin task-manager

run-worker:
	@cargo run --bin task-worker

run-operator:
	@cargo run --bin task-operator

# Build Docker images
docker-build:
	@echo "Building Docker images..."
	@docker build -f deploy/docker/Dockerfile.manager -t task-manager:latest .
	@docker build -f deploy/docker/Dockerfile.worker -t task-worker:latest .
	@docker build -f deploy/docker/Dockerfile.operator -t task-operator:latest .

# Integration test environment
test-env-up: docker-up k8s-deploy
	@echo "Test environment is ready!"

test-env-down: docker-down k8s-clean
	@echo "Test environment cleaned up!"

# Benchmarks
bench:
	@echo "Running benchmarks..."
	@cargo bench --workspace

# Documentation
docs:
	@echo "Building documentation..."
	@cargo doc --workspace --no-deps --open

# Release
release: test clippy audit
	@echo "Preparing release..."
	@cargo build --workspace --release
	@echo "Release artifacts built!"