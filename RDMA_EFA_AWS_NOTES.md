# RDMA/EFA on AWS — Quick Notes

Last updated: 2026-02-07

## Summary
- **Fabric validated**: two-host `ucx_perftest` shows `tag(srd/efa_0:1)`.
- **Java app**: worker-address exchange + warmup/barrier is implemented; use **TCP baseline** first, then **hybrid** (`tcp,srd,self`).

## Instances (fill in)
- A (server): `<A_PRIV>` / `<A_PUB>`
- B (client): `<B_PRIV>` / `<B_PUB>`
- Key: `~/.ssh/rdma-efa-key.pem`

## Native UCX sanity (two-host)
Server (A):
```bash
export UCX_LOG_LEVEL=info
export UCX_TLS=srd
export UCX_NET_DEVICES=efa_0:1
/opt/ucx/bin/ucx_perftest -c 0 -p 13337
```
Client (B):
```bash
export UCX_LOG_LEVEL=info
export UCX_TLS=srd
export UCX_NET_DEVICES=efa_0:1
/opt/ucx/bin/ucx_perftest <A_PRIV> -t tag_lat -c 1 -p 13337
```
Expected proof line: `tag(srd/efa_0:1)`

Successful `ucx_perftest` output (sample):
```
Waiting for connection...
Accepted connection from <B_PRIV>:60232
+----------------------------------------------------------------------------------------------------------+
| API:          protocol layer                                                                             |
| Test:         tag match latency                                                                          |
| Data layout:  (automatic)                                                                                |
| Send memory:  host                                                                                       |
| Recv memory:  host                                                                                       |
| Message size: 8                                                                                          |
| Window size:  1                                                                                          |
+----------------------------------------------------------------------------------------------------------+
```

Client-side summary (sample):
```
+--------------+--------------+------------------------------+---------------------+-----------------------+
|              |              |        latency (usec)        |   bandwidth (MB/s)  |  message rate (msg/s) |
+--------------+--------------+----------+---------+---------+----------+----------+-----------+-----------+
|    Stage     | # iterations | 50.0%ile | average | overall |  average |  overall |  average  |  overall  |
+--------------+--------------+----------+---------+---------+----------+----------+-----------+-----------+
```

## Build the Java app (both instances)
```bash
cd ~/ozone-AI
mvn -DskipTests package
mvn -q -DskipTests dependency:build-classpath -Dmdep.outputFile=cp.txt
```

## Java run — TCP baseline (known-good)
Server (A):
```bash
export JUCX_LIB_PATH=/opt/ucx/lib/libjucx.so
export LD_LIBRARY_PATH=/opt/ucx/lib:$LD_LIBRARY_PATH
export UCX_LOG_LEVEL=info
export UCX_TLS=tcp,self
export UCX_NET_DEVICES=eth0
export RDMA_OP_TIMEOUT_MS=300000
export UCX_FILE_ROOT=/tmp
export UCX_WARN_UNUSED_ENV_VARS=n

CP="target/classes:$(cat cp.txt)"
java -cp "$CP" org.openucx.rdma.UcxRdmaServerMain 0.0.0.0 23456 |& tee /tmp/java_server.log
```
Client (B):
```bash
export JUCX_LIB_PATH=/opt/ucx/lib/libjucx.so
export LD_LIBRARY_PATH=/opt/ucx/lib:$LD_LIBRARY_PATH
export UCX_LOG_LEVEL=info
export UCX_TLS=tcp,self
export UCX_NET_DEVICES=eth0
export RDMA_OP_TIMEOUT_MS=300000

CP="target/classes:$(cat cp.txt)"
java -cp "$CP" org.openucx.rdma.UcxRdmaClientMain <A_PRIV> 23456 "Hello EFA" |& tee /tmp/java_client.log
```

## Java run — Hybrid (TCP + SRD)
```bash
export UCX_TLS=tcp,srd,self
export UCX_NET_DEVICES=eth0,efa_0:1
```
Check logs for `tag(srd/efa_0:1)` vs `tag(tcp/eth0)`.

## File transfer test
Server (A):
```bash
dd if=/dev/urandom of=/tmp/ucx_payload.bin bs=1M count=8
```
Client (B):
```bash
java -cp "$CP" org.openucx.rdma.UcxRdmaClientMain --get <A_PRIV> 23456 ucx_payload.bin /tmp/ucx_fetch.bin 262144
sha256sum /tmp/ucx_payload.bin /tmp/ucx_fetch.bin
```

## Troubleshooting (fast)
- Server waiting on warmup recv: client not sending warmup (old build or client not running).
- Client stuck waiting for server address: server crashed before sending it.
- UCX warns about `UCX_FILE_ROOT`: app env var; suppress with `UCX_WARN_UNUSED_ENV_VARS=n`.

## Notes
- `RDMA_OP_TIMEOUT_MS` controls app timeouts.
- Keep `JUCX_LIB_PATH=/opt/ucx/lib/libjucx.so` and `LD_LIBRARY_PATH=/opt/ucx/lib`.
