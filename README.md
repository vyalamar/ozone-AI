# ozone-AI

High-performance RDMA file transfer system using UCX (Unified Communication X) and JUCX (Java UCX bindings), designed for AWS EFA (Elastic Fabric Adapter).

## Features

- **Worker-address exchange** for optimal RDMA transport selection
- **Tagged send/receive** operations for reliable message passing
- **Chunked file transfer** with configurable chunk sizes (default 256KB)
- **Path traversal protection** with configurable file root directory
- **Zero-copy transfers** using direct ByteBuffers
- **Echo mode** for connectivity testing
- **File GET mode** for high-speed file transfers

## Quick Start

### Prerequisites

- UCX 1.20.x with JUCX bindings installed (e.g., in `/opt/ucx`)
- Java 8 or higher
- Maven 3.x

### Build

```bash
mvn clean package
mvn dependency:build-classpath -Dmdep.outputFile=cp.txt
```

### Run (TCP Mode - Recommended)

**Server:**
```bash
export UCX_TLS=tcp,sm,self
export UCX_NET_DEVICES=eth0
export JUCX_LIB_PATH=/opt/ucx/lib/libjucx.so
export LD_LIBRARY_PATH=/opt/ucx/lib:$LD_LIBRARY_PATH
export UCX_FILE_ROOT=/tmp

CP="target/classes:$(cat cp.txt)"
java -cp "$CP" org.openucx.rdma.UcxRdmaServerMain 0.0.0.0 23456
```

**Client (Echo Test):**
```bash
export UCX_TLS=tcp,sm,self
export UCX_NET_DEVICES=eth0
export JUCX_LIB_PATH=/opt/ucx/lib/libjucx.so
export LD_LIBRARY_PATH=/opt/ucx/lib:$LD_LIBRARY_PATH

CP="target/classes:$(cat cp.txt)"
java -cp "$CP" org.openucx.rdma.UcxRdmaClientMain <server-ip> 23456 "Hello UCX"
```

**Client (File Transfer):**
```bash
# Same environment as echo test
java -cp "$CP" org.openucx.rdma.UcxRdmaClientMain --get <server-ip> 23456 /tmp/file.bin /tmp/output.bin
```

## Architecture

### Protocol Design

- **Bootstrap Connection**: Plain TCP socket for worker address exchange
- **Worker Address Exchange**: Enables UCX to select optimal transport (EFA, TCP, shared memory)
- **Tagged Operations**: 
  - Request tag: `0x1111` (client → server)
  - Response tag: `0x2222` (server → client)
- **File Transfer Protocol**:
  1. Client sends: `GET <path>` request
  2. Server responds: 8-byte length header (Big Endian)
  3. Server streams: File data in chunks
  4. Client reassembles and writes to disk

### Security

- **Path Traversal Protection**: `resolveSafePath()` validates all file requests
- **Configurable Root**: Set `UCX_FILE_ROOT` environment variable (default: `/tmp`)
- **Normalized Paths**: All paths are normalized and checked against root directory

## AWS EFA Setup

See [RDMA_EFA_AWS_NOTES.md](RDMA_EFA_AWS_NOTES.md) for detailed AWS EFA setup instructions.

### Known Issues with EFA SRD

**Issue**: UCX 1.20.0 selects EFA SRD transport (`tag(srd/efa_0:1)`) but data transfer times out with keepalive errors.

**Status**: Under investigation. Current workaround is to use TCP mode which provides reliable high-speed transfers over standard Ethernet.

**Workaround**: Use `UCX_TLS=tcp,sm,self` and `UCX_NET_DEVICES=eth0` (see Quick Start above).

## Testing

```bash
# Run unit tests
mvn test

# Integration tests require UCX native libraries
# They will be skipped automatically if UCX is not available
```

## Project Structure

```
src/main/java/org/openucx/rdma/
├── RdmaProtocol.java          # Protocol constants and utilities
├── FileTransferUtil.java      # File I/O and path validation
├── UcxNative.java            # Native library loader
├── UcxRdmaServer.java        # Server implementation
├── UcxRdmaServerMain.java    # Server entry point
├── UcxRdmaClient.java        # Client implementation
└── UcxRdmaClientMain.java    # Client entry point

scripts/
├── aws_launch_efa_instances.sh  # AWS instance provisioning
└── diagnose_efa.sh              # EFA diagnostics
```

## Performance Considerations

- **Chunk Size**: Default 256KB, configurable via client parameter
- **Direct ByteBuffers**: Used for zero-copy operations
- **Non-blocking Operations**: Manual progress polling for optimal control
- **Transport Selection**: UCX automatically selects fastest available transport

## License

[Specify license here]

## Contributing

[Add contribution guidelines here]

## References

- [UCX Documentation](https://openucx.org/)
- [JUCX Examples](https://github.com/openucx/ucx/tree/master/bindings/java)
- [AWS EFA Documentation](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/efa.html)
