#!/usr/bin/env bash
set -euo pipefail

echo "========================================"
echo "EFA Connectivity Test Script"
echo "========================================"
echo ""

# Parse arguments
if [ $# -lt 1 ]; then
    echo "Usage: $0 {server|client} [server-ip]"
    echo ""
    echo "Examples:"
    echo "  On server: $0 server"
    echo "  On client: $0 client 172.31.10.70"
    exit 1
fi

MODE=$1
SERVER_IP=${2:-}

# Set UCX environment
export UCX_LOG_LEVEL=debug
export UCX_TLS=srd
export UCX_NET_DEVICES=efa_0:1
export UCX_SOCKADDR_TLS_PRIORITY=srd
export LD_LIBRARY_PATH=/opt/ucx/lib:${LD_LIBRARY_PATH:-}

echo "Mode: $MODE"
echo "UCX_TLS: $UCX_TLS"
echo "UCX_NET_DEVICES: $UCX_NET_DEVICES"
echo ""

# Test 1: Verify EFA device
echo "==> Test 1: EFA Device Check"
if ibv_devinfo -d efa_0 >/dev/null 2>&1; then
    echo "✓ EFA device efa_0 found"
    ibv_devinfo -d efa_0 | head -10
else
    echo "✗ EFA device efa_0 NOT found"
    exit 1
fi
echo ""

# Test 2: Check EFA interface
echo "==> Test 2: EFA Interface Check"
if ip link show efa0 >/dev/null 2>&1; then
    echo "✓ EFA network interface found"
    ip addr show efa0
else
    echo "⚠ EFA interface efa0 not found (this is expected for some setups)"
fi
echo ""

# Test 3: UCX transport availability
echo "==> Test 3: UCX SRD Transport"
if ucx_info -d 2>&1 | grep -q "Transport: srd"; then
    echo "✓ UCX SRD transport available"
    ucx_info -d 2>&1 | grep -A 2 "Transport: srd"
else
    echo "✗ UCX SRD transport NOT available"
    exit 1
fi
echo ""

# Test 4: Run ucx_perftest
echo "==> Test 4: UCX Perftest"
if [ "$MODE" = "server" ]; then
    echo "Starting server on port 13337..."
    echo "Waiting for client connection..."
    /opt/ucx/bin/ucx_perftest -c 0 -p 13337 -t tag_lat -s 8 -n 10
elif [ "$MODE" = "client" ]; then
    if [ -z "$SERVER_IP" ]; then
        echo "Error: Server IP required for client mode"
        exit 1
    fi
    echo "Connecting to server at $SERVER_IP:13337..."
    sleep 2  # Give server time to start
    /opt/ucx/bin/ucx_perftest "$SERVER_IP" -p 13337 -t tag_lat -s 8 -n 10
else
    echo "Invalid mode: $MODE"
    exit 1
fi

echo ""
echo "========================================"
echo "Test Complete"
echo "========================================"
