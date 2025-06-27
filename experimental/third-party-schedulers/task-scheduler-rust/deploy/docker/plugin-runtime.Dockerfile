# Plugin runtime container
FROM rust:1.75-slim as builder

# Install build dependencies
RUN apt-get update && apt-get install -y \
    pkg-config \
    libssl-dev \
    && rm -rf /var/lib/apt/lists/*

# Create workspace
WORKDIR /build

# Copy workspace files
COPY Cargo.toml Cargo.lock ./
COPY crates/ ./crates/
COPY task-macro/ ./task-macro/

# Build plugin runtime
RUN cargo build --release --package task-plugins

# Runtime stage
FROM debian:bookworm-slim

# Install runtime dependencies
RUN apt-get update && apt-get install -y \
    ca-certificates \
    libssl3 \
    && rm -rf /var/lib/apt/lists/*

# Create plugin user
RUN useradd -m -u 1000 -s /bin/bash plugin

# Create directories
RUN mkdir -p /plugins /plugin-cache /plugin-config && \
    chown -R plugin:plugin /plugins /plugin-cache /plugin-config

# Copy runtime binary
COPY --from=builder /build/target/release/libta* /usr/local/lib/

# Create plugin runner script
COPY deploy/scripts/plugin-runner.sh /usr/local/bin/plugin-runner
RUN chmod +x /usr/local/bin/plugin-runner

# Switch to plugin user
USER plugin

# Set environment
ENV RUST_LOG=info
ENV PLUGIN_DIR=/plugins
ENV PLUGIN_CACHE_DIR=/plugin-cache
ENV PLUGIN_CONFIG_DIR=/plugin-config

# Volume for plugins
VOLUME ["/plugins", "/plugin-cache", "/plugin-config"]

# Default command
CMD ["/usr/local/bin/plugin-runner"]