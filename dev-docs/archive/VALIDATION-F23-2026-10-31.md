# F23 — contain development FE host exposure

## Scope and status

Repository defaults and refusal gates are implemented and isolated live acceptance
passes. Final **130/130 frozen-source tests pass, no skips**. After the initial
closeout, the user separately authorized **stopping only the retained API-24 FE**.
`doris-api24-fe` is now stopped with no effective published ports; its container,
image, mounts and volumes are retained. The old configured all-interface mappings
remain, so **do not restart it before a separately authorized loopback-only
recreation**. No migration/recreation was performed. Host firewall, external
reachability and account grants were not probed. See the final containment entry.

No Doris rebuild/re-vendor, dependency/API/JNI/catalog pin, plugin payload,
capability change, credential rotation, retained metadata reset, firewall or host
SSH configuration change is part of F23. F24/F25 remain separately qualified;
this task does not seed a lake, rerun full correctness or widen production support.
Verification and stop-only containment preceded the subsequent user-requested
F23 commit/push.

## Remediation

- Default Compose publishes only FE HTTP/MySQL, explicitly on host `127.0.0.1`.
  RPC `9020`, edit-log `9010` and all BE ports are not host-published. Internal
  FE/BE addresses, networks, health checks and container-local automation stay
  unchanged. The passwordless root convention is confined, not disguised as
  production authentication.
- Remote administration uses **explicit authenticated SSH forwarding** to those
  loopback client ports. The client forward also binds loopback; host keys must
  be verified and only trusted development administrators should have this SSH
  access. There is no public-bind opt-in that exposes passwordless SQL. Direct
  remote Doris service exposure requires a separate account/TLS/authorization/
  ingress design, including compatibility with automation.
- `plugin_preflight.py` checks rendered FE/BE policy **and** existing containers'
  requested `HostConfig.PortBindings` / effective `NetworkSettings.Ports`.
  Public/unspecified host IPs, internal ports, UDP, automatic/random host ports,
  publish-all, host/shared-container network mode and missing inspection metadata
  fail closed. EXPOSE-only null entries do not count as host publications. Explicit
  IPv4/IPv6 loopback HTTP/MySQL and no-publication sandboxes are allowed.
- Existing BE ownership is now checked in read-only preflight as well as later
  runtime attestation; foreign containers are never adopted. Deployment already
  revalidates the target throughout claim/install/start/attestation. Network
  refusal is therefore reapplied rather than inferred from safe Compose text.
- **Restart is not migration.** `docker start` keeps old published ports. Unsafe
  running or stopped legacy instances are refused before installation/allocation,
  not recreated, stopped or declared secure. An unsafe state appearing after claim
  acquisition also refuses without touching FE/plugin state. No `down -v` shortcut
  or default credentials change is used to force a green result.

This is host-publication containment, not tenant isolation: same-network peers,
trusted host users and Docker administrators can still reach/administer the FE.
Direct container routing, daemon/firewall policy and sibling substrate exposure
are separate. Docker documents that pre-28.0.0 engines can expose loopback-published
ports to same-L2 hosts; use Docker 28+ and review host/network policy. Source:
[Docker port publishing](https://docs.docker.com/engine/network/port-publishing/),
checked 2026-10-31. Podman inspection metadata is subject to the same conservative
checks; this does not certify every rootless/remote-daemon network implementation.

## Baseline and regressions

The previous committed preflight (`081d146`) was run in memory against the new
regression cases, without editing the repository or contacting retained services.
It accepted all **20 deliberately unsafe subcases**: public/internal Compose
ports, legacy public requested/effective mappings, host/shared networking and
publish-all. `/tmp/opencode/f23-before.log` retains those intended before-fix
assertion failures. They are not failures of the final implementation.

Tests cover fresh/existing/stopped/safely unpublished targets, IPv4/IPv6 explicit
loopback, bad metadata and public/internal mappings. Whole-driver full/headless/
preflight-only refusals assert no install, start, teardown, allocation or current
passing evidence. A post-claim binding change is independently refused with the
original plugin file untouched. Real Compose rendering checks both current default
and unchanged historical R07 definitions; it does not launch or repin R07.

Final **130/130 Python tests, no skips**, pass across six disjoint shards with
Python/shell/Compose YAML source hashes unchanged. Focused policy/whole-driver
tests, Bash syntax and whitespace checks pass.
`check java17PluginTest assemble`/Detekt pass; the Java-17 runtime floor scans
24,630 classes. Unchanged JVM/build-logic tests are correctly UP-TO-DATE, not
claimed as fresh JVM test executions (`/tmp/opencode/f23-build-check.log`).
Development test corrections supplied the required render-only R07 staging
variable and used its historical `fe`/`be` service names; no retained R07 service
was started or edited. The first complete 130-method run retained one failure in
the overlap test: an intentionally paused inventory query kept the generic
one-second failure-fixture timeout while the rival now performed additional
read-only preflight checks. The overlap test alone now has a 30-second startup
coordination budget (production query timeout still caps at 15 seconds); production
timeouts and all failure assertions are unchanged. The first run remains failed
development evidence, not final passing certification. The authoritative rerun is
`/tmp/opencode/f23-final-v2-results.json`, with Python/shell/**Compose YAML** source
hashes frozen before/after all six disjoint shards.
The corrected attestation-overlap test also passes three separate repetitions
while the full suite is running (`/tmp/opencode/f23-overlap-repeat.log`); these
repetitions are separate stress evidence, not additional unique suite methods.
`/tmp/opencode/f23-closeout-results.json` independently verifies final suite/source
bindings, live FE/BE/network/SSH evidence, credential-free receipts, unchanged
retained state/default artifact/Quack report, no remaining owned Docker resources
or SSH processes/private keys, and the still-open retained exposure decision.

## Real FE/BE and authenticated tunnel acceptance

**Passed**: `/tmp/opencode/f24-e-9mbw6msy/acceptance.json`.
`network_acceptance.py` reuses F24's UUID-owned collision-checked sandbox, existing
image/artifact pins and cleanup controller. The test copies the actual rendered
default port policy, substitutes only unused high loopback host port numbers, and
deploys the unchanged default A through the real `--up-only` stopped-install/
fresh-process/provider gate. B/producer inputs are validated by the shared fixture
but not deployed/used to seed data in F23.

- Real pinned API-24 FE/full ASAN BE starts, registers and passes provider/source
  attestation and container-local `SELECT 1`. No F25 correctness claim is emitted.
- Requested and effective mappings are exactly `127.0.0.1:41385 → 8030/tcp` and
  `127.0.0.1:36659 → 9030/tcp`; no wildcard/IPv6 or internal host mapping exists.
- Host-loopback HTTP returns 200; the MySQL protocol handshake is valid (advertised
  compatibility version `5.7.99`, **not** the Doris source identity).
- A separate disposable Python client on the owned bridge reaches FE HTTP/MySQL
  directly (positive control) but cannot connect to either published port via the
  host's non-loopback gateway. This checks a real network path, not only YAML text.
- A disposable **loopback-only**, key-only sshd with generated test host/client
  keys rejects an unapproved key. The approved key establishes explicit loopback
  HTTP/MySQL forwards; HTTP returns 200 and the SQL handshake matches. Server/client
  processes are stopped and unencrypted test private keys are removed afterward.
  The host's SSH service/account files, passwords, firewall and known-hosts files
  are unchanged. This exercises authenticated forwarding, not a fleet SSH audit or
  per-user Doris SQL authorization.
- Docker server is 29.6.0. All owned Docker resources are cleaned. Protected
  retained container IDs/images/running/started-at state and runtime config,
  default plugin bytes and unrelated Quack report match before/after. Copied
  tooling/default Compose/runner hashes were independently matched after the run.

Raw deployment/bridge/SSH evidence, copied sources and logs remain inside that
fixture directory. `compose/README.md` documents the exact opt-in command and
remote tunnel recipe. No external host/account/network session was used.

## Initial operational finding (before authorized containment)

Read-only inspection confirmed the retained API-24 FE remains **running with
all-interface mappings** (`0.0.0.0` and `::`) on HTTP `18324` and SQL `19324`.
Its Compose labels identify project `doris-api24-qualification` and configuration
`/home/jayson/DEV/OSS/doris-ducklake-api24-62e3d81a/output/qualification/docker-compose.yml`,
outside this repository; that file and its FE metadata/log/plugin volumes were
not changed. Containment of this retained qualification service is not implied
by fixing `compose/docker-compose.yml` here.
The older `doris-ducklake-fe` is stopped, but its configured bindings remain
wildcard on `8030/9030/9010/9020`; it must not be restarted as though safe defaults
had updated it. Existing ownership/mount refusal remains in place as well.
`/tmp/opencode/f23-retained-readonly-policy.json` applies the current policy to
actual read-only container inspections: running API-24/stopped legacy public
bindings are refused, while the stopped R07 loopback bindings pass. This probe
issues no SQL and mutates none of them.

Choose explicit containment and, if needed, a metadata-preserving recreation plan
before changing either retained stack. Do not infer that the repository fix has
closed their operational exposure, and do not infer internet reachability from a
bind address alone. Production authentication/TLS, broader R07, F01/F02 maintenance
safety/authority and general M17 remain separate work.

## Authorized stop-only containment (2026-10-31)

The user explicitly requested: **"Stop the retained API24 FE; preserve everything."**
Verified container ID `32eb5891a58b8601b32f8a6895713927106b57bb723aec50475be1ac6fd056eb`,
Compose project/service/config labels and metadata/log/plugin mounts before issuing
only `docker stop --time 60 <verified-ID>`. Confirmed stopped at
`2026-10-31T05:19:06.995569008Z`, with no effective published ports. No Docker
start/recreation/removal, Compose down, volume deletion, firewall or retained
configuration change was performed.

Independent before/after inspection confirms the same container/image/started-at,
every complete mount record, all container/volume inventory, other containers'
running state and protected config/default-plugin/Quack file hashes. Docker
reordered the FE's mount list on stop: the first order-sensitive audit refused
certification, then destination-sorted equality confirmed identical mount records.
Normal shutdown may flush metadata/logs; byte-identical volume contents are not
claimed. Containers, data volumes and metadata were not removed or reset.

Evidence:
`/tmp/opencode/f23-retained-stop-b91e7c88f3524d638f032ff2b1e47bd5/result.json`,
with private before/after inspections alongside it. The initial acceptance and
closeout evidence remain historical **before-stop** observations, not relabelled
as proof that the retained FE stayed running after this new explicit instruction.

Immediate stop-only containment is complete. The stopped container still stores
wildcard HTTP `18324` / MySQL `19324` publication settings: restarting it would
republish them. Any loopback-only recreation/resumption remains a separate explicit
decision and must preserve existing metadata/log/plugin volumes.
