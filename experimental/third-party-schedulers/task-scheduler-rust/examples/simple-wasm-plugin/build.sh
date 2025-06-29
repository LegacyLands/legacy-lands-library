#!/bin/bash

# Build script for simple WASM plugin

set -e

echo "Building simple WASM plugin..."

# Install wasm32-unknown-unknown target if not already installed
rustup target add wasm32-unknown-unknown

# Build the plugin
cargo build --release --target wasm32-unknown-unknown

# Copy the WASM file to a known location
mkdir -p ../../target/wasm-plugins
cp target/wasm32-unknown-unknown/release/simple_wasm_plugin.wasm ../../target/wasm-plugins/simple.wasm

# Print size
echo "Simple WASM plugin built successfully!"
ls -lh ../../target/wasm-plugins/simple.wasm