#!/bin/bash

# Build script for WASM plugin

set -e

echo "Building WASM plugin..."

# Install wasm32-unknown-unknown target if not already installed
rustup target add wasm32-unknown-unknown

# Build the plugin
cargo build --release --target wasm32-unknown-unknown

# Copy the WASM file to a known location
mkdir -p ../../target/wasm-plugins
cp target/wasm32-unknown-unknown/release/wasm_plugin_example.wasm ../../target/wasm-plugins/echo.wasm
cp echo.json ../../target/wasm-plugins/echo.json

# Optional: Optimize the WASM file with wasm-opt if available
if command -v wasm-opt &> /dev/null; then
    echo "Optimizing WASM with wasm-opt..."
    wasm-opt -Oz \
        ../../target/wasm-plugins/echo.wasm \
        -o ../../target/wasm-plugins/echo-optimized.wasm
    mv ../../target/wasm-plugins/echo-optimized.wasm ../../target/wasm-plugins/echo.wasm
fi

# Print size
echo "WASM plugin built successfully!"
ls -lh ../../target/wasm-plugins/echo.wasm