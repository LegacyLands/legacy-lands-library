#!/bin/bash
# Enable and run end-to-end tests

set -e

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "${YELLOW}Enabling end-to-end tests...${NC}"

# Move disabled tests to enabled location
E2E_DIR="crates/task-tests/tests"
DISABLED_DIR="$E2E_DIR/disabled"

if [ -d "$DISABLED_DIR" ]; then
    echo "Moving tests from disabled directory..."
    
    # Move e2e tests
    if [ -d "$DISABLED_DIR/e2e" ]; then
        mv "$DISABLED_DIR/e2e" "$E2E_DIR/"
        echo -e "${GREEN}✓ Enabled e2e tests${NC}"
    fi
    
    # Move integration tests
    if [ -d "$DISABLED_DIR/integration" ]; then
        mv "$DISABLED_DIR/integration" "$E2E_DIR/"
        echo -e "${GREEN}✓ Enabled integration tests${NC}"
    fi
    
    # Move common utilities
    if [ -d "$DISABLED_DIR/common" ]; then
        mv "$DISABLED_DIR/common" "$E2E_DIR/"
        echo -e "${GREEN}✓ Enabled common test utilities${NC}"
    fi
    
    # Move individual test files
    for test_file in "$DISABLED_DIR"/*.rs; do
        if [ -f "$test_file" ]; then
            mv "$test_file" "$E2E_DIR/"
            echo -e "${GREEN}✓ Enabled $(basename $test_file)${NC}"
        fi
    done
    
    # Remove empty disabled directory
    rmdir "$DISABLED_DIR" 2>/dev/null || true
    
    echo -e "\n${GREEN}All tests have been enabled!${NC}"
else
    echo -e "${YELLOW}Tests are already enabled or disabled directory not found${NC}"
fi

# Update Cargo.toml to include the tests
CARGO_TOML="crates/task-tests/Cargo.toml"
if [ -f "$CARGO_TOML" ]; then
    echo -e "\n${YELLOW}Updating Cargo.toml...${NC}"
    
    # Add test dependencies if not present
    if ! grep -q "hdrhistogram" "$CARGO_TOML"; then
        cat >> "$CARGO_TOML" << EOF

[dev-dependencies]
hdrhistogram = "7.5"
tempfile = "3.8"
notify = "6.1"
EOF
        echo -e "${GREEN}✓ Added test dependencies${NC}"
    fi
fi

# Create test configuration
echo -e "\n${YELLOW}Creating test configuration...${NC}"
cat > "test-config.toml" << EOF
# Test Configuration
[test]
# Run integration tests
integration_tests = true

# Run e2e tests
e2e_tests = true

# Run performance tests
performance_tests = false

# Test environment
[test.environment]
postgres_url = "postgres://postgres:postgres@localhost:5432/task_scheduler_test"
mongodb_url = "mongodb://localhost:27017/task_scheduler_test"
nats_url = "nats://localhost:4222"

# Test timeouts
[test.timeouts]
unit_test = 30
integration_test = 60
e2e_test = 120
performance_test = 300
EOF
echo -e "${GREEN}✓ Created test-config.toml${NC}"

# Run the tests
echo -e "\n${YELLOW}Running enabled tests...${NC}"
echo -e "${YELLOW}Note: Some tests require external services (PostgreSQL, MongoDB, NATS)${NC}"
echo -e "${YELLOW}Make sure these services are running or use Docker:${NC}"
echo -e "  docker run -d -p 5432:5432 -e POSTGRES_PASSWORD=postgres postgres:15"
echo -e "  docker run -d -p 27017:27017 mongo:6"
echo -e "  docker run -d -p 4222:4222 nats:2.10"
echo

# Run tests
echo -e "\n${GREEN}To run all tests:${NC}"
echo "  cargo test --workspace"
echo
echo -e "${GREEN}To run e2e tests:${NC}"
echo "  cargo test -p task-tests --test e2e"
echo
echo -e "${GREEN}To run with ignored tests:${NC}"
echo "  cargo test -p task-tests -- --ignored"
echo
echo -e "${GREEN}To run performance tests:${NC}"
echo "  cargo test -p task-tests --test performance_test -- --ignored --nocapture"