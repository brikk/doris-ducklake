"""Opt-in F23 real FE/BE loopback and authenticated SSH-forwarding acceptance.

Reuses F24's UUID-owned sandbox, pins, collision checks and cleanup. Requires
local Docker, OpenSSH client/server/keygen and the same A/B/producer inputs as
plugin_acceptance.py; only A is deployed, in deployment-only mode. No retained
service, host SSH configuration, account credential or firewall is changed.
"""

import argparse
from contextlib import contextmanager
import getpass
import json
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

from plugin_acceptance import Acceptance, write_json
from plugin_preflight import development_compose_network, development_container_network, file_hash, require


def unused_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def wait_listener(process, port):
    deadline = time.monotonic() + 10
    while process.poll() is None:
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.2):
                return
        except OSError:
            require(time.monotonic() < deadline, "Owned SSH listener did not become ready")
            time.sleep(0.05)
    raise ValueError("Owned SSH process exited before readiness; inspect its log")


def mysql_version(port):
    with socket.create_connection(("127.0.0.1", port), timeout=5) as sock:
        data = sock.makefile("rb")
        header = data.read(4)
        require(len(header) == 4, "Missing MySQL handshake header")
        length = int.from_bytes(header[:3], "little")
        require(1 < length < 4096, "Unexpected MySQL handshake size")
        payload = data.read(length)
        require(len(payload) == length and payload[0] == 10, "Unexpected MySQL handshake")
        return payload[1:].split(b"\0", 1)[0].decode("ascii")


def http_status(port):
    # Never inherit a user's HTTP proxy or send credentials outside loopback.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(f"http://127.0.0.1:{port}/", timeout=10) as response:
            status = response.status
    except urllib.error.HTTPError as error:
        status = error.code
    require(status in {200, 401}, "Unexpected FE HTTP response")
    return status


@contextmanager
def ssh_forward(root, ports):
    """A private, loopback-only key-authenticated sshd, never the host daemon."""
    tools = {name: shutil.which(name) for name in ("ssh", "sshd", "ssh-keygen")}
    require(all(tools.values()), "F23 SSH acceptance requires ssh, sshd and ssh-keygen")
    directory = root / "ssh"
    directory.mkdir(mode=0o700)
    for name in ("host", "allowed", "denied"):
        subprocess.run([tools["ssh-keygen"], "-q", "-t", "ed25519", "-N", "", "-f", str(directory / name)],
                       check=True, capture_output=True, timeout=15)
    port = unused_port()
    config = directory / "sshd_config"
    config.write_text(f"""Port {port}
ListenAddress 127.0.0.1
HostKey {directory / 'host'}
PidFile {directory / 'pid'}
AuthorizedKeysFile {directory / 'allowed.pub'}
AllowUsers {getpass.getuser()}
AuthenticationMethods publickey
PubkeyAuthentication yes
PasswordAuthentication no
KbdInteractiveAuthentication no
UsePAM no
StrictModes no
PermitRootLogin prohibit-password
AllowTcpForwarding local
GatewayPorts no
PermitOpen 127.0.0.1:{ports[8030]} 127.0.0.1:{ports[9030]}
AllowAgentForwarding no
X11Forwarding no
PermitTTY no
LogLevel VERBOSE
""")
    known = directory / "known_hosts"
    known.write_text(f"[127.0.0.1]:{port} " + (directory / "host.pub").read_text())
    common = [tools["ssh"], "-F", "/dev/null", "-p", str(port), "-o", "BatchMode=yes", "-o", "IdentitiesOnly=yes",
              "-o", "IdentityAgent=none", "-o", "StrictHostKeyChecking=yes", "-o", "UserKnownHostsFile=" + str(known),
              "-o", "GlobalKnownHostsFile=/dev/null", "-o", "ExitOnForwardFailure=yes", "-o", "ConnectTimeout=5"]
    server = client = None
    try:
        with (directory / "server.log").open("w") as log:
            server = subprocess.Popen([tools["sshd"], "-D", "-e", "-f", str(config)], stdout=log, stderr=log)
            wait_listener(server, port)
            denied = subprocess.run([*common, "-i", str(directory / "denied"), "-N", getpass.getuser() + "@127.0.0.1"],
                                    text=True, capture_output=True, timeout=10)
            require(denied.returncode == 255 and "Permission denied" in denied.stderr, "Unapproved SSH key was not refused")
            forwarded = {target: unused_port() for target in ports}
            args = [*common, "-i", str(directory / "allowed"), "-N"]
            for target, local in forwarded.items():
                args += ["-L", f"127.0.0.1:{local}:127.0.0.1:{ports[target]}"]
            args += [getpass.getuser() + "@127.0.0.1"]
            with (directory / "client.log").open("w") as client_log:
                client = subprocess.Popen(args, stdout=client_log, stderr=client_log)
                for local in forwarded.values():
                    wait_listener(client, local)
                yield forwarded
    finally:
        for process in (client, server):
            if process is not None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
        # Disposable unencrypted test keys must not remain in evidence.
        for name in ("host", "allowed", "denied"):
            (directory / name).unlink(missing_ok=True)


class NetworkAcceptance(Acceptance):
    def prepare(self):
        super().prepare()
        self.result.update(schema="doris-development-network-acceptance-v1", f23_runner_sha256=file_hash(Path(__file__)))
        shutil.copyfile(Path(__file__), self.root / "controller-sources/network_acceptance.py")
        base = self.data("compose", "-f", self.source / "docker-compose.yml", "config", "--format", "json")
        development_compose_network(base)
        ports = base["services"]["doris-fe"]["ports"]
        require({(p["target"], p["host_ip"], p["protocol"]) for p in ports} ==
                {(8030, "127.0.0.1", "tcp"), (9030, "127.0.0.1", "tcp")} and len(ports) == 2,
                "Default FE does not have exactly two loopback-only client ports")
        self.result["default_compose_sha256"] = file_hash(self.source / "docker-compose.yml")
        self.ports = {p["target"]: unused_port() for p in ports}
        config = json.loads(self.doris_file.read_text())
        config["services"]["doris-fe"]["ports"] = [dict(p, published=str(self.ports[p["target"]])) for p in ports]
        write_json(self.doris_file, config)

    def run(self):
        print(f"F23 evidence: {self.root}", flush=True)
        try:
            self.prepare()
            self.phase("loopback-deployment", "A")
            fe = self.data("container", "inspect", self.fe)[0]
            be = self.data("container", "inspect", self.be)[0]
            development_container_network(fe, "doris-fe")
            development_container_network(be, "doris-be")
            self.result["fe_bindings"] = {k: fe[k] for k in ("HostConfig", "NetworkSettings")}
            self.result["direct_loopback"] = {"mysql_version": mysql_version(self.ports[9030]),
                                              "http_status": http_status(self.ports[8030])}
            require(self.query("SELECT 1") == "1", "Container-local SQL failed")
            # Probe from a separate client on the owned bridge, not host-local:
            # direct FE traffic works while the host's non-loopback gateway
            # cannot reach either host-published client port.
            script = """import json,socket,sys
out={}
for host,port,expected in json.loads(sys.argv[1]):
    try:
        with socket.create_connection((host,port),timeout=3): connected=True
    except OSError: connected=False
    out[host+':'+str(port)]=connected
    assert connected is expected,(host,port,connected,expected)
print(json.dumps(out))
"""
            probes = [(self.fe, 8030, True), (self.fe, 9030, True)] + [
                ("f23-host", port, False) for port in self.ports.values()]
            self.result["bridge_probe"] = json.loads(self.command([
                "run", "--rm", "--pull=never", "--network", self.project + "-substrate-network",
                "--add-host", "f23-host:host-gateway", "--entrypoint", "python3",
                self.result["images"]["python:3.12-slim"], "-c", script, json.dumps(probes)]))
            with ssh_forward(self.root, self.ports) as ports:
                require(mysql_version(ports[9030]) == self.result["direct_loopback"]["mysql_version"], "SSH SQL tunnel mismatch")
                self.result["ssh"] = {"unapproved_key_refused": True, "approved_key_forwarding": True,
                                      "mysql_handshake": True, "http_status": http_status(ports[8030])}
            require(file_hash(self.source / "docker-compose.yml") == self.result["default_compose_sha256"] and
                    all(file_hash(self.source / name) == digest for name, digest in self.result["copied_tooling_sha256"].items()),
                    "Sources changed during network acceptance")
            self.result.update(status="passed", loopback_only=True, real_sql_and_be_registration=True)
        except BaseException as error:
            self.result.update(status="failed", error=str(error))
            raise
        finally:
            try:
                self.cleanup()
                if "protected_before" in self.result:
                    self.result["protected_after"] = self.protected()
                    require(self.result["protected_before"] == self.result["protected_after"], "Retained state changed")
            except BaseException as error:
                self.result.update(status="failed", cleanup_or_protection_error=str(error))
                raise
            finally:
                write_json(self.root / "acceptance.json", self.result)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle-a", type=Path, required=True)
    parser.add_argument("--bundle-b", type=Path, required=True)
    parser.add_argument("--duckdb", type=Path, required=True)
    parser.add_argument("--evidence-root", type=Path, default=Path("/tmp/opencode"))
    args = parser.parse_args()
    try:
        NetworkAcceptance(args.bundle_a, args.bundle_b, args.duckdb, args.evidence_root).run()
    except (ValueError, OSError, KeyError, TypeError, RuntimeError, subprocess.SubprocessError) as error:
        print(f"F23 acceptance failed: {error}", file=sys.stderr)
        return 1
    print("PASS: real FE loopback, bridge refusal, authenticated SSH forwarding; owned resources cleaned.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
