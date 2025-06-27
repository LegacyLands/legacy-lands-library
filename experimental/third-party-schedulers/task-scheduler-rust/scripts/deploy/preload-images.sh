#!/bin/bash
# Preload commonly used images to Minikube

echo "📦 Preloading images to Minikube"
echo "================================"

# List of images to preload
IMAGES=(
    "postgres:16-alpine"
    "nats:2.10-alpine"
    "prom/prometheus:latest"
    "grafana/grafana:latest"
    "jaegertracing/all-in-one:latest"
)

# Function to load image
load_image() {
    local image=$1
    echo -e "\n🔄 Loading $image..."
    
    # Check if image exists locally
    if docker images --format "{{.Repository}}:{{.Tag}}" | grep -q "^${image}$"; then
        echo "  ✓ Image already exists locally"
    else
        echo "  📥 Pulling image..."
        docker pull "$image" || {
            echo "  ❌ Failed to pull $image"
            return 1
        }
    fi
    
    # Load to minikube
    echo "  📤 Loading to Minikube..."
    minikube image load "$image" || {
        echo "  ❌ Failed to load $image to Minikube"
        return 1
    }
    
    echo "  ✅ $image loaded successfully"
}

# Load all images
for image in "${IMAGES[@]}"; do
    load_image "$image"
done

echo -e "\n✅ Image preloading complete!"