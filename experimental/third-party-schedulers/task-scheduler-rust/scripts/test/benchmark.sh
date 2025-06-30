#!/bin/bash
# Extreme Performance Benchmark for Rust Task Scheduler
# Tests maximum throughput without rate limiting

set -e

# Color codes
GREEN='\033[0;32m'
RED='\033[0;31m'
CYAN='\033[0;36m'
PURPLE='\033[0;35m'
NC='\033[0m'

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
LOAD_TEST="$PROJECT_ROOT/target/release/load-test"

# Default settings for extreme performance testing
TEST_DURATION=60
TEST_CONNECTIONS=500
SLEEP_RANGE="10-50"  # Realistic operation latency
BATCH_MODE=false
BATCH_SIZE=100
FORMAT="json"  # json or bincode

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --duration)
            TEST_DURATION="$2"
            shift 2
            ;;
        --connections)
            TEST_CONNECTIONS="$2"
            shift 2
            ;;
        --sleep-range)
            SLEEP_RANGE="$2"
            shift 2
            ;;
        --batch)
            BATCH_MODE=true
            BATCH_SIZE="${2:-100}"
            shift 2
            ;;
        --format)
            FORMAT="$2"
            shift 2
            ;;
        --help)
            echo "Extreme Performance Benchmark - Find maximum throughput"
            echo ""
            echo "Usage: $0 [options]"
            echo ""
            echo "Options:"
            echo "  --duration <seconds>    Test duration (default: 60)"
            echo "  --connections <num>     Number of connections (default: 500)"
            echo "  --sleep-range <ms-ms>   Sleep duration range in ms (default: 10-50)"
            echo "  --batch <size>          Enable batch mode with size (default: 100)"
            echo "  --format <json|bincode> Serialization format (default: json)"
            echo ""
            echo "Example:"
            echo "  $0                      # Run default extreme test"
            echo "  $0 --duration 120       # Run for 2 minutes"
            echo "  $0 --batch 1000         # Use batch mode with 1000 tasks/batch"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

# Check prerequisites
check_prerequisites() {
    if ! docker ps | grep -q task-scheduler-manager; then
        echo -e "${RED}Error: Services are not running!${NC}"
        echo "Please run: docker compose up -d"
        exit 1
    fi
    
    if [[ ! -f "$LOAD_TEST" ]]; then
        echo -e "${CYAN}Building load test tool...${NC}"
        cargo build --release -p task-load-test
    fi
}

# Main test function
run_extreme_test() {
    local timestamp=$(date +%Y%m%d_%H%M%S)
    local results_dir="$PROJECT_ROOT/benchmark-extreme-$timestamp"
    mkdir -p "$results_dir"
    
    echo -e "${PURPLE}╔════════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${PURPLE}║        Rust Task Scheduler - Extreme Performance Test          ║${NC}"
    echo -e "${PURPLE}╚════════════════════════════════════════════════════════════════╝${NC}"
    
    echo -e "\n${CYAN}📊 Test Configuration:${NC}"
    echo -e "${CYAN}  - Duration: ${TEST_DURATION}s${NC}"
    echo -e "${CYAN}  - Connections: ${TEST_CONNECTIONS}${NC}"
    echo -e "${CYAN}  - RPS: UNLIMITED (finding maximum throughput)${NC}"
    echo -e "${CYAN}  - Sleep range: ${SLEEP_RANGE}ms (realistic load)${NC}"
    echo -e "${CYAN}  - Format: bincode (always)${NC}"
    if [ "$BATCH_MODE" = true ]; then
        echo -e "${CYAN}  - Batch mode: Enabled (${BATCH_SIZE} tasks/batch)${NC}"
    fi
    
    # Build command
    local cmd="$LOAD_TEST"
    cmd="$cmd --rps 0"  # 0 means unlimited
    cmd="$cmd --duration $TEST_DURATION"
    cmd="$cmd --connections $TEST_CONNECTIONS"
    cmd="$cmd --endpoint http://localhost:50051"
    cmd="$cmd --methods echo,add,multiply,concat"
    cmd="$cmd --include-sleep"
    cmd="$cmd --sleep-range $SLEEP_RANGE"
    # Format is now always bincode, no need to specify
    
    if [ "$BATCH_MODE" = true ]; then
        cmd="$cmd --batch-mode --batch-size $BATCH_SIZE"
    fi
    
    # Run test
    echo -e "\n${CYAN}🚀 Running extreme performance test...${NC}"
    local log_file="$results_dir/extreme-test.log"
    
    eval "$cmd" | tee "$log_file"
    
    # Extract results
    local actual_rps=$(grep "Actual RPS:" "$log_file" | awk '{print $3}')
    local success_rate=$(grep "Success rate:" "$log_file" | awk '{print $3}')
    local p99=$(grep "P99:" "$log_file" | awk '{print $2}')
    
    # Show summary
    echo -e "\n${PURPLE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${GREEN}🎯 Maximum Throughput: ${actual_rps} RPS${NC}"
    echo -e "${GREEN}✅ Success Rate: ${success_rate}${NC}"
    echo -e "${GREEN}⏱  P99 Latency: ${p99}${NC}"
    echo -e "${PURPLE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    
    # Save results
    cat > "$results_dir/summary.txt" <<EOF
Extreme Performance Test Results
================================
Date: $(date)
Duration: ${TEST_DURATION}s
Connections: ${TEST_CONNECTIONS}
Sleep Range: ${SLEEP_RANGE}ms
Batch Mode: ${BATCH_MODE}
Batch Size: ${BATCH_SIZE}

Results:
--------
Maximum Throughput: ${actual_rps} RPS
Success Rate: ${success_rate}
P99 Latency: ${p99}

Logs: $log_file
EOF
    
    echo -e "\n${CYAN}📁 Results saved to: $results_dir${NC}"
    
    # Show resource usage
    echo -e "\n${CYAN}📊 Resource Usage:${NC}"
    docker stats --no-stream | grep -E "(NAME|task-scheduler-manager|task-scheduler-postgres|task-scheduler-redis)" | head -5
}

# Monitor during test (optional background function)
monitor_resources() {
    while true; do
        clear
        echo -e "${CYAN}Real-time Resource Monitor${NC}"
        echo "=========================="
        docker stats --no-stream | grep -E "(NAME|task-scheduler-manager|postgres|redis|worker)" | head -10
        sleep 2
    done
}

# Main execution
main() {
    check_prerequisites
    
    # Optional: Start resource monitor in background
    if [[ "${MONITOR:-false}" == "true" ]]; then
        monitor_resources &
        MONITOR_PID=$!
        trap "kill $MONITOR_PID 2>/dev/null" EXIT
    fi
    
    run_extreme_test
    
    echo -e "\n${GREEN}✅ Test completed successfully!${NC}"
    echo -e "${CYAN}Check Grafana at http://localhost:3000 for detailed metrics${NC}"
}

# Run
main "$@"