#!/bin/bash

# Script to stop system MongoDB and Redis services
# This needs to be run with sudo

echo "This script needs sudo permissions to stop system services."
echo "Please run: sudo ./scripts/stop-system-services.sh"
echo ""
echo "Commands that will be executed:"
echo "  sudo systemctl stop mongod"
echo "  sudo systemctl stop redis"
echo ""
echo "Alternatively, you can run these commands manually:"
echo "  sudo systemctl stop mongod redis"
echo ""
echo "Or kill the processes directly:"
echo "  sudo kill -TERM $(pgrep -f 'mongod --config')"
echo "  sudo kill -TERM $(pgrep -f 'redis-server 127.0.0.1:6379')"