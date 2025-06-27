#!/bin/bash
# Common utilities and configurations for scripts

# Get the script directory and project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# Colors for output
export RED='\033[0;31m'
export GREEN='\033[0;32m'
export YELLOW='\033[1;33m'
export BLUE='\033[0;34m'
export PURPLE='\033[0;35m'
export CYAN='\033[0;36m'
export NC='\033[0m' # No Color

# Symbols
export CHECK_MARK="✓"
export CROSS_MARK="✗"
export WARNING_SIGN="⚠"
export INFO_SIGN="ℹ"

# Default configurations
export DEFAULT_NAMESPACE="task-scheduler"
export DEFAULT_PROXY_URL="http://127.0.0.1:10808"
export DEFAULT_TIMEOUT=300

# Function to print colored output
print_color() {
    local color=$1
    local message=$2
    echo -e "${color}${message}${NC}"
}

# Function to print section header
print_section() {
    local title=$1
    echo -e "\n${BLUE}════════════════════════════════════════════════════════${NC}"
    echo -e "${YELLOW}${title}${NC}"
    echo -e "${BLUE}════════════════════════════════════════════════════════${NC}"
}

# Function to print success message
print_success() {
    echo -e "${GREEN}${CHECK_MARK} $1${NC}"
}

# Function to print error message
print_error() {
    echo -e "${RED}${CROSS_MARK} $1${NC}"
}

# Function to print warning message
print_warning() {
    echo -e "${YELLOW}${WARNING_SIGN} $1${NC}"
}

# Function to print info message
print_info() {
    echo -e "${CYAN}${INFO_SIGN} $1${NC}"
}

# Function to check if a command exists
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

# Function to check required commands
check_requirements() {
    local requirements=("$@")
    local missing=()
    
    for cmd in "${requirements[@]}"; do
        if ! command_exists "$cmd"; then
            missing+=("$cmd")
        fi
    done
    
    if [ ${#missing[@]} -gt 0 ]; then
        print_error "Missing required commands: ${missing[*]}"
        return 1
    fi
    
    return 0
}

# Function to setup proxy
setup_proxy() {
    local proxy_url=${1:-$DEFAULT_PROXY_URL}
    
    if [ -n "$proxy_url" ]; then
        export HTTP_PROXY=$proxy_url
        export HTTPS_PROXY=$proxy_url
        export http_proxy=$proxy_url
        export https_proxy=$proxy_url
        print_info "Proxy configured: $proxy_url"
    fi
}

# Function to unset proxy
unset_proxy() {
    unset HTTP_PROXY HTTPS_PROXY http_proxy https_proxy
    print_info "Proxy configuration removed"
}

# Function to wait for pod to be ready
wait_for_pod() {
    local namespace=$1
    local selector=$2
    local timeout=${3:-$DEFAULT_TIMEOUT}
    
    print_info "Waiting for pod with selector '$selector' to be ready..."
    if kubectl wait --for=condition=ready pod \
        -l "$selector" \
        -n "$namespace" \
        --timeout="${timeout}s" >/dev/null 2>&1; then
        print_success "Pod is ready"
        return 0
    else
        print_error "Pod failed to become ready within ${timeout}s"
        return 1
    fi
}

# Function to check if we're in CI environment
is_ci() {
    [ -n "$CI" ] || [ -n "$CONTINUOUS_INTEGRATION" ] || [ -n "$GITHUB_ACTIONS" ]
}

# Function to get current git branch
get_git_branch() {
    git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "unknown"
}

# Function to get git commit hash
get_git_commit() {
    git rev-parse --short HEAD 2>/dev/null || echo "unknown"
}

# Function to get current timestamp
get_timestamp() {
    date '+%Y%m%d-%H%M%S'
}

# Export project root for use in other scripts
export PROJECT_ROOT