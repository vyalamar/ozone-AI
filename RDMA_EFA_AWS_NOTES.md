# RDMA/EFA AWS Notes

This file documents the current AWS setup, instance access details, and the
exact steps performed so far. It also includes a replay checklist for the
second instance.

## Current AWS context

- AWS profile: `<aws-profile>`
- Region: `us-east-1`
- AZ used: `us-east-1a`
- Default VPC: `<vpc-id>`
- Placement group: `efa-rdma-pg` (cluster)
- Security group: `efa-rdma-sg`
- Key pair: `efa-rdma-key`
- Local key path: `~/.ssh/efa-rdma-key.pem`
- Instance type: `c5n.9xlarge` (EFA-enabled, x86_64)
- AMI (AL2): `/aws/service/ami-amazon-linux-latest/amzn2-ami-hvm-x86_64-gp2`

## Summary (status)

- EFA is present on both instances (`fi_info -p efa`, `ibv_devinfo`).
- UCX v1.20.x is built with EFA; `ucx_info -d` shows `srd/efa_0:1`.
- Two-host `ucx_perftest` shows EFA SRD data path (`inter-node cfg#0 tag(srd/efa_0:1)`).
- Java JUCX app runs and loads `libjucx.so` via `UcxNative`.
- Java client/server currently selects TCP (`tag(tcp/eth0)`) because the sample uses
  socket-address endpoints; TCP is required for the handshake.

## 3-step setup summary (per instance)

1) Install EFA + dependencies, then reboot to load kernel modules.
2) Build UCX from `v1.20.x` with `--with-efa`, then install to `/opt/ucx`.
3) Verify EFA SRD via `ucx_info -d | egrep -i 'efa|srd'` and run `ucx_perftest`.

## Current instances

Use these IDs and IPs to find or connect to the current instances:

- Instance A
  - Instance ID: `<instance-a-id>`
  - Private IP: `<instance-a-private-ip>`
  - Public IP: `<instance-a-public-ip>`
- Instance B
  - Instance ID: `<instance-b-id>`
  - Private IP: `<instance-b-private-ip>`
  - Public IP: `<instance-b-public-ip>`

Refresh status and IPs if needed:

```bash
aws ec2 describe-instances --region us-east-1 \
  --instance-ids <instance-a-id> <instance-b-id> \
  --query 'Reservations[].Instances[].[InstanceId,PrivateIpAddress,PublicIpAddress,State.Name]' \
  --output table
```

## SSH access

```bash
ssh -i ~/.ssh/efa-rdma-key.pem ec2-user@<instance-a-public-ip>
ssh -i ~/.ssh/efa-rdma-key.pem ec2-user@<instance-b-public-ip>
```

## What was done on Instance A (<instance-a-private-ip>)

1) Installed EFA software

```bash
sudo yum -y update
sudo yum -y install git java-11-amazon-corretto-devel maven gcc make autoconf automake libtool cmake \
  numactl-devel hwloc-devel rdma-core rdma-core-devel libibverbs-utils

curl -O https://efa-installer.amazonaws.com/aws-efa-installer-latest.tar.gz
tar -xzf aws-efa-installer-latest.tar.gz
cd aws-efa-installer
sudo ./efa_installer.sh -y
sudo reboot
```

2) Verified EFA

```bash
fi_info -p efa
ibv_devinfo | head
```

Observed `fi_info -p efa` output:

```
provider: efa
    fabric: efa-direct
    domain: efa_0-rdm
    version: 204.0
    type: FI_EP_RDM
    protocol: FI_PROTO_EFA
provider: efa
    fabric: efa
    domain: efa_0-rdm
    version: 204.0
    type: FI_EP_RDM
    protocol: FI_PROTO_EFA
provider: efa
    fabric: efa
    domain: efa_0-dgrm
    version: 204.0
    type: FI_EP_DGRAM
    protocol: FI_PROTO_EFA
```

Observed `ibv_devinfo | head` output:

```
hca_id:	efa_0
	transport:			unspecified (4)
	fw_ver:				0.0.0.0
	node_guid:			0000:0000:0000:0000
	sys_image_guid:			0000:0000:0000:0000
	vendor_id:			0x1d0f
	vendor_part_id:			61344
	hw_ver:				0xEFA0
	phys_port_cnt:			1
		port:	1
```

3) Built UCX with EFA support (v1.20.x)

```bash
cd ~
git clone https://github.com/openucx/ucx.git ucx-efa
cd ucx-efa
git checkout v1.20.x
./autogen.sh

rm -rf build && mkdir build && cd build
export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which javac))))
export CFLAGS="-I/opt/amazon/efa/include"
export LDFLAGS="-L/opt/amazon/efa/lib64"
../configure --prefix=/opt/ucx --with-efa --with-verbs=/usr --with-rdmacm=/usr --with-java="$JAVA_HOME"
make -j"$(nproc)"
sudo make install

echo 'export PATH=/opt/ucx/bin:$PATH' >> ~/.bashrc
echo 'export LD_LIBRARY_PATH=/opt/ucx/lib:$LD_LIBRARY_PATH' >> ~/.bashrc
source ~/.bashrc
```

4) Verified UCX sees EFA SRD

```bash
ucx_info -d | egrep -i 'efa|srd'
```

Expected output includes:

```
# Memory domain: efa_0
#      Transport: srd
#         Device: efa_0:1
```

Observed `ucx_info -d | egrep -i 'efa|srd'` output:

```
# Memory domain: efa_0
#         Device: efa_0:1
#  System device: efa_0 (1)
#      Transport: srd
#         Device: efa_0:1
#  System device: efa_0 (1)
```

5) Single-host smoke tests

Shared-memory (not EFA):

```bash
export UCX_LOG_LEVEL=info
export UCX_TLS=sm,self
/opt/ucx/bin/ucx_perftest -c 0
/opt/ucx/bin/ucx_perftest 127.0.0.1 -t tag_lat -c 1
```

EFA SRD forced on one host (still intra-node, but confirms transport):

```bash
export UCX_LOG_LEVEL=info
export UCX_TLS=srd
export UCX_NET_DEVICES=efa_0:1
PRIVATE_IP="<instance-a-private-ip>"
/opt/ucx/bin/ucx_perftest -c 0 -p 13337
/opt/ucx/bin/ucx_perftest "$PRIVATE_IP" -t tag_lat -c 1 -p 13337
```

Logs showed `tag(srd/efa_0:1)` but also `intra-node`, so it was not a true
multi-host fabric test.

## Replay steps on Instance B (<instance-b-private-ip>)

Run the same sequence as Instance A:

1) Install EFA and reboot.
2) Verify `fi_info -p efa` and `ibv_devinfo`.
3) Build UCX from `v1.20.x` with `--with-efa`.
4) Verify `ucx_info -d | egrep -i 'efa|srd'`.

## Two-host EFA test (real RDMA)

Server (Instance A):

```bash
export UCX_LOG_LEVEL=info
export UCX_TLS=srd,sm,self
export UCX_NET_DEVICES=efa_0:1
/opt/ucx/bin/ucx_perftest -c 0 -p 13337
```

Client (Instance B):

```bash
export UCX_LOG_LEVEL=info
export UCX_TLS=srd,sm,self
export UCX_NET_DEVICES=efa_0:1
/opt/ucx/bin/ucx_perftest <instance-a-private-ip> -t tag_lat -c 1 -p 13337
```

Observed server log (Instance A):

```
Waiting for connection...
Accepted connection from <instance-b-private-ip>:60232
+----------------------------------------------------------------------------------------------------------+
| API:          protocol layer                                                                             |
| Test:         tag match latency                                                                          |
| Data layout:  (automatic)                                                                                |
| Send memory:  host                                                                                       |
| Recv memory:  host                                                                                       |
| Message size: 8                                                                                          |
| Window size:  1                                                                                          |
+----------------------------------------------------------------------------------------------------------+
[1769931205.105551] [ip-instance-a:25302:0]     ucp_context.c:2463 UCX  INFO  Version 1.20.0 (loaded from /opt/ucx/lib/libucp.so.0)
[1769931205.120773] [ip-instance-a:25302:0]          parser.c:2366 UCX  INFO  UCX_* env variables: UCX_TLS=srd,sm,self UCX_NET_DEVICES=efa_0:1 UCX_LOG_LEVEL=info
[1769931205.164294] [ip-instance-a:25302:0]      ucp_worker.c:1912 UCX  INFO    perftest inter-node cfg#0 tag(srd/efa_0:1)
```

Observed client log (Instance B):

```
+--------------+--------------+------------------------------+---------------------+-----------------------+
|              |              |        latency (usec)        |   bandwidth (MB/s)  |  message rate (msg/s) |
+--------------+--------------+----------+---------+---------+----------+----------+-----------+-----------+
|    Stage     | # iterations | 50.0%ile | average | overall |  average |  overall |  average  |  overall  |
+--------------+--------------+----------+---------+---------+----------+----------+-----------+-----------+
[1769931205.109058] [ip-instance-b:24021:0]     ucp_context.c:2463 UCX  INFO  Version 1.20.0 (loaded from /opt/ucx/lib/libucp.so.0)
[1769931205.122853] [ip-instance-b:24021:0]          parser.c:2366 UCX  INFO  UCX_* env variables: UCX_TLS=srd,sm,self UCX_NET_DEVICES=efa_0:1 UCX_LOG_LEVEL=info
[1769931205.123118] [ip-instance-b:24021:0]      ucp_worker.c:1912 UCX  INFO    perftest inter-node cfg#0 tag(srd/efa_0:1)
```

## Java demo (JUCX) across two hosts

## Java code changes in this repo

- Added runnable entry points:
  - `src/main/java/org/openucx/rdma/UcxRdmaServerMain.java`
  - `src/main/java/org/openucx/rdma/UcxRdmaClientMain.java`
  - These print UCX env vars and run a simple echo exchange.
- Added native loader to force `libjucx.so`:
  - `src/main/java/org/openucx/rdma/UcxNative.java`
  - Attempts `JUCX_LIB_PATH`, `JUCX_HOME`, `UCX_HOME`, `/opt/ucx/lib/libjucx.so`,
    then falls back to `System.loadLibrary("jucx")`.
- Server/client now call `UcxNative.load()` before creating UCX contexts:
  - `src/main/java/org/openucx/rdma/UcxRdmaServer.java`
  - `src/main/java/org/openucx/rdma/UcxRdmaClient.java`
- `pom.xml` uses `jucx.version` property (default `1.20.0`) for the JUCX dependency.

### Sync the repo to both instances

```bash
scp -i ~/.ssh/efa-rdma-key.pem -r ~/ozone-AI ec2-user@<instance-a-public-ip>:~/ozone-AI
scp -i ~/.ssh/efa-rdma-key.pem -r ~/ozone-AI ec2-user@<instance-b-public-ip>:~/ozone-AI
```

### Build JUCX (must match UCX v1.20.x)

On each instance:

```bash
# If bindings/java lacks a POM, use the generated build tree:
#   ~/ucx-efa/build/bindings/java
cd ~/ucx-efa/bindings/java
export UCX_HOME=/opt/ucx
mvn -DskipTests install

# Confirm the JUCX version:
awk -F'[<>]' '/<version>/{print $3; exit}' pom.xml
```

Note: the native JNI library (`libjucx.so`) comes from the UCX C build
(`--with-java`) and is installed into `/opt/ucx/lib`. The Maven step installs
the JUCX jar into `~/.m2`.

### Update app JUCX version

`pom.xml` uses the `jucx.version` property (default: `1.20.0`). If your
locally built JUCX version differs (e.g., `1.20.0-SNAPSHOT`), update the
property to match.

### Build the app on each instance

```bash
cd ~/ozone-AI
mvn -DskipTests package
mvn -q -DskipTests dependency:build-classpath -Dmdep.outputFile=cp.txt
```

### Run server/client with EFA

Server on Instance A:

```bash
source ~/.bashrc
export UCX_LOG_LEVEL=info
export UCX_TLS=srd,tcp,sm,self
export UCX_NET_DEVICES=efa_0:1,eth0
export UCX_SOCKADDR_TLS_PRIORITY=tcp
export JUCX_LIB_PATH=/opt/ucx/lib/libjucx.so
export LD_LIBRARY_PATH=/opt/ucx/lib:$LD_LIBRARY_PATH

CP="target/classes:$(cat cp.txt)"
java -cp "$CP" org.openucx.rdma.UcxRdmaServerMain 0.0.0.0 23456
```

Client on Instance B:

```bash
source ~/.bashrc
export UCX_LOG_LEVEL=info
export UCX_TLS=srd,tcp,sm,self
export UCX_NET_DEVICES=efa_0:1,eth0
export UCX_SOCKADDR_TLS_PRIORITY=tcp
export JUCX_LIB_PATH=/opt/ucx/lib/libjucx.so
export LD_LIBRARY_PATH=/opt/ucx/lib:$LD_LIBRARY_PATH

CP="target/classes:$(cat cp.txt)"
java -cp "$CP" org.openucx.rdma.UcxRdmaClientMain <instance-a-private-ip> 23456 "Hello EFA"
```

Note: `tcp` must be present in `UCX_TLS` for the socket connection manager
handshake. If you remove `tcp`, UCX cannot establish the initial connection and
you will see `Destination is unreachable` or `device eth0 is not enabled`.
Data still flows over EFA SRD (`srd/efa_0:1`) once connected.

Observed Java server log (Instance A):

```
UCX_TLS=srd,sm,self
UCX_NET_DEVICES=efa_0:1,eth0
UCX_LOG_LEVEL=info
Starting UCX server on 0.0.0.0:23456
[1769939803.621826] [ip-instance-a:19548:0]     ucp_context.c:2463 UCX  INFO  Version 1.20.0 (loaded from /opt/ucx/lib/libucp.so.0.0.0)
[1769939803.635954] [ip-instance-a:19548:0]          parser.c:2359 UCX  WARN  unused environment variable: UCX_HOME
[1769939803.635954] [ip-instance-a:19548:0]          parser.c:2359 UCX  WARN  (set UCX_WARN_UNUSED_ENV_VARS=n to suppress this warning)
[1769939803.635967] [ip-instance-a:19548:0]          parser.c:2366 UCX  INFO  UCX_* env variables: UCX_TLS=srd,sm,self UCX_SOCKADDR_TLS_PRIORITY=tcp UCX_NET_DEVICES=efa_0:1,eth0 UCX_LOG_LEVEL=info
```

Observed Java client log (Instance B):

```
UCX_TLS=srd,sm,self,tcp
UCX_NET_DEVICES=eth0,efa_0:1
UCX_LOG_LEVEL=info
Connecting to <instance-a-private-ip>:23456
Loaded JUCX native library: /opt/ucx/lib/libjucx.so
[1769939840.412983] [ip-instance-b:21955:0]     ucp_context.c:2463 UCX  INFO  Version 1.20.0 (loaded from /opt/ucx/lib/libucp.so.0)
[1769939840.427823] [ip-instance-b:21955:0]          parser.c:2366 UCX  INFO  UCX_* env variables: UCX_TLS=srd,sm,self,tcp UCX_SOCKADDR_TLS_PRIORITY=tcp UCX_NET_DEVICES=eth0,efa_0:1 UCX_LOG_LEVEL=info
[1769939840.437278] [ip-instance-b:21955:0]      ucp_worker.c:1912 UCX  INFO  jucx intra-node cfg#1 tag(tcp/eth0)
```

Observed Java client log (Instance B, port 23457):

```
UCX_TLS=srd,tcp,sm,self
UCX_NET_DEVICES=efa_0:1,eth0
UCX_LOG_LEVEL=info
Connecting to <instance-a-private-ip>:23457
Loaded JUCX native library: /opt/ucx/lib/libjucx.so
[1769940529.720565] [ip-instance-b:28833:0]     ucp_context.c:2463 UCX  INFO  Version 1.20.0 (loaded from /opt/ucx/lib/libucp.so.0)
[1769940529.735495] [ip-instance-b:28833:0]          parser.c:2366 UCX  INFO  UCX_* env variables: UCX_NET_DEVICES=efa_0:1,eth0 UCX_LOG_LEVEL=info UCX_TLS=srd,tcp,sm,self UCX_SOCKADDR_TLS_PRIORITY=tcp
[1769940529.745041] [ip-instance-b:28833:0]      ucp_worker.c:1912 UCX  INFO  jucx intra-node cfg#1 tag(tcp/eth0)
```

Note: the captured output above does not include a `Reply:` line. Ensure the
server is running on the same port and capture both server and client output
to confirm the Java echo exchange.

Note: the `UCX_HOME` warning is harmless. Unset `UCX_HOME` or set
`UCX_WARN_UNUSED_ENV_VARS=n` if you want to silence it.

If UCX prints transport selection (verbosity depends on build and log level),
`tag(srd/efa_0:1)` indicates EFA SRD data path. The sample client log above
shows `tag(tcp/eth0)`, which means UCX selected TCP for the data transport.
Use `ucx_info -d` or `ucx_perftest` to confirm EFA is available, and keep
`tcp` in `UCX_TLS` for the handshake.

## How to prove EFA/RDMA vs TCP

The most reliable proof is a two-host `ucx_perftest` run with EFA-only
transports. This uses UCX worker-address exchange and shows the exact data
transport selected.

Server (Instance A):

```bash
export UCX_LOG_LEVEL=info
export UCX_TLS=srd
export UCX_NET_DEVICES=efa_0:1
/opt/ucx/bin/ucx_perftest -c 0 -p 13337
```

Client (Instance B):

```bash
export UCX_LOG_LEVEL=info
export UCX_TLS=srd
export UCX_NET_DEVICES=efa_0:1
/opt/ucx/bin/ucx_perftest <instance-a-private-ip> -t tag_lat -c 1 -p 13337
```

Expected proof lines:

```
perftest inter-node cfg#0 tag(srd/efa_0:1)
```

Interpretation:

- `tag(srd/efa_0:1)` means the data path is EFA SRD (RDMA).
- `tag(tcp/eth0)` means the data path is TCP.
- `ucx_info -d | egrep -i 'efa|srd'` proves the EFA transport is available.
- `fi_info -p efa` and `ibv_devinfo` prove the EFA device exists in the OS.

Why the Java sample shows TCP:

- The sample uses socket-address endpoints (`setSocketAddress`), which require
  the TCP connection manager. UCX will often select TCP for the data path when
  the connection is established via sockets.
- To force EFA for Java, switch to UCX worker-address exchange and create
  endpoints from `UcpAddress` rather than socket address (future change).

## Pending work

- Build JUCX from the same UCX tree (`~/ucx-efa/bindings/java`) and update
  `ozone-AI/pom.xml` to the matching version.
- Run the Java client/server across both instances and capture the echo logs.
- Optional: update the Java sample to exchange worker addresses and force
  EFA SRD for the data path (so logs show `tag(srd/efa_0:1)`).
