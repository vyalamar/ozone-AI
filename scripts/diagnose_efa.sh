#!/usr/bin/env bash
set -euo pipefail

echo "=========================================="
echo "EFA/UCX Diagnostic Script"
echo "=========================================="
echo ""

# 1. Check EFA device
echo "==> Checking EFA Device"
if command -v fi_info >/dev/null 2>&1; then
    echo "EFA provider info:"
    fi_info -p efa 2>&1 | head -20
else
    echo "WARN: fi_info not found"
fi
echo ""

# 2. Check EFA interface
echo "==> Checking EFA Network Interface"
if ip link show efa0 >/dev/null 2>&1; then
    echo "EFA interface status:"
    ip addr show efa0
else
    echo "WARN: efa0 interface not found"
fi
echo ""

# 3. Check UCX build
echo "==> Checking UCX Configuration"
if command -v ucx_info >/dev/null 2>&1; then
    echo "UCX transports available:"
    ucx_info -d 2>&1 | grep -E "(Transport:|Device:|Memory domain:)" | head -20
    echo ""
    echo "UCX version:"
    ucx_info -v 2>&1 | head -5
else
    echo "WARN: ucx_info not found"
fi
echo ""

# 4. Check JUCX library
echo "==> Checking JUCX Library"
if [ -f /opt/ucx/lib/libjucx.so ]; then
    echo "JUCX library found: /opt/ucx/lib/libjucx.so"
    ls -lh /opt/ucx/lib/libjucx.so
else
    echo "ERROR: /opt/ucx/lib/libjucx.so not found"
fi
echo ""

# 5. Check kernel modules
echo "==> Checking EFA Kernel Modules"
if lsmod | grep -q efa; then
    echo "EFA kernel module loaded:"
    lsmod | grep efa
else
    echo "WARN: EFA kernel module not loaded"
fi
echo ""

# 6. Check RDMA devices
echo "==> Checking RDMA Devices"
if command -v ibv_devinfo >/dev/null 2>&1; then
    echo "RDMA devices:"
    ibv_devinfo 2>&1 | head -15
else
    echo "WARN: ibv_devinfo not found"
fi
echo ""

# 7. Suggest UCX environment variables
echo "==> Recommended UCX Environment Variables"
echo ""
echo "For RDMA with TCP fallback:"
cat <<'EOF'
export UCX_LOG_LEVEL=info
export UCX_TLS=srd,tcp,sm,self
export UCX_NET_DEVICES=efa_0:1,eth0
export UCX_SOCKADDR_TLS_PRIORITY=tcp
export JUCX_LIB_PATH=/opt/ucx/lib/libjucx.so
export LD_LIBRARY_PATH=/opt/ucx/lib:$LD_LIBRARY_PATH
EOF
echo ""

echo "For EFA-only (no fallback):"
cat <<'EOF'
export UCX_LOG_LEVEL=info
export UCX_TLS=srd,sm,self
export UCX_NET_DEVICES=efa_0:1
export JUCX_LIB_PATH=/opt/ucx/lib/libjucx.so
export LD_LIBRARY_PATH=/opt/ucx/lib:$LD_LIBRARY_PATH
EOF
echo ""

# 8. Check if this is an EFA-capable instance
echo "==> Checking Instance Type"
if command -v ec2-metadata >/dev/null 2>&1; then
    INSTANCE_TYPE=$(ec2-metadata --instance-type 2>/dev/null | awk '{print $2}' || echo "unknown")
    echo "Instance type: $INSTANCE_TYPE"
    
    case "$INSTANCE_TYPE" in
        c5n.*|c6gn.*|p4d.*|p4de.*|p5.*|dl1.*|dl2q.*|trn1.*)
            echo "✓ Instance type supports EFA"
            ;;
        *)
            echo "✗ Instance type may not support EFA"
            ;;
    esac
else
    echo "WARN: Cannot determine instance type"
fi
echo ""

echo "=========================================="
echo "Diagnostic complete"
echo "=========================================="
