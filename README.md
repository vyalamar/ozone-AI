# ozone-AI

UCX/JUCX RDMA sample for AWS EFA. Minimal client/server echo plus a file GET path.

## Build
```bash
mvn -DskipTests package
mvn -q -DskipTests dependency:build-classpath -Dmdep.outputFile=cp.txt
```

## Run (TCP baseline)
Use TCP first to confirm the Java path is correct.

Server:
```bash
export JUCX_LIB_PATH=/opt/ucx/lib/libjucx.so
export LD_LIBRARY_PATH=/opt/ucx/lib:$LD_LIBRARY_PATH
export UCX_LOG_LEVEL=info
export UCX_TLS=tcp,self
export UCX_NET_DEVICES=eth0
export UCX_FILE_ROOT=/tmp
export RDMA_OP_TIMEOUT_MS=300000
export UCX_WARN_UNUSED_ENV_VARS=n

CP="target/classes:$(cat cp.txt)"
java -cp "$CP" org.openucx.rdma.UcxRdmaServerMain 0.0.0.0 23456
```

Client (echo):
```bash
export JUCX_LIB_PATH=/opt/ucx/lib/libjucx.so
export LD_LIBRARY_PATH=/opt/ucx/lib:$LD_LIBRARY_PATH
export UCX_LOG_LEVEL=info
export UCX_TLS=tcp,self
export UCX_NET_DEVICES=eth0
export RDMA_OP_TIMEOUT_MS=300000

CP="target/classes:$(cat cp.txt)"
java -cp "$CP" org.openucx.rdma.UcxRdmaClientMain <server-ip> 23456 "Hello UCX"
```

Client (file GET):
```bash
java -cp "$CP" org.openucx.rdma.UcxRdmaClientMain --get <server-ip> 23456 /tmp/file.bin /tmp/output.bin
```

## Run (Hybrid TCP + SRD)
```bash
export UCX_TLS=tcp,srd,self
export UCX_NET_DEVICES=eth0,efa_0:1
```
Check logs for `tag(srd/efa_0:1)` vs `tag(tcp/eth0)`.

## Notes
- Request tag `0x1111`, response tag `0x2222`.
- File GET: client sends `GET <path>`, server replies with 8‑byte length header (big‑endian) then payload.
- File access is restricted to `UCX_FILE_ROOT` (default `/tmp`).

## Tests
```bash
mvn test
```
Integration tests are skipped if UCX native libs are missing.

## Layout
```
src/main/java/org/openucx/rdma/
├── RdmaProtocol.java
├── FileTransferUtil.java
├── UcxNative.java
├── UcxRdmaServer.java
├── UcxRdmaServerMain.java
├── UcxRdmaClient.java
└── UcxRdmaClientMain.java

scripts/
├── aws_launch_efa_instances.sh
├── diagnose_efa.sh
└── test_efa_connectivity.sh
```

## AWS setup
See `RDMA_EFA_AWS_NOTES.md`.
