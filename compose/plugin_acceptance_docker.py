"""Test-only real-Docker recorder/guard and narrow stale-inventory injection.

No production bypass flag is introduced. Normal replies are passed through;
negative acceptance substitutes only the version in a verified real SQL row.
"""

import json
import os
import re
from pathlib import Path
import subprocess
import sys


def main():
    spec = json.loads(Path(os.environ["F24_ACCEPTANCE_DOCKER"]).read_text())
    args = sys.argv[1:]
    real = spec["docker"]

    def owned(container):
        result = subprocess.run([real, "container", "inspect", container], text=True, capture_output=True)
        if result.returncode:
            return False
        instance = json.loads(result.stdout)[0]
        labels = instance["Config"].get("Labels") or {}
        return (labels.get("com.docker.compose.project") in spec["projects"] or
                bool(labels.get("dev.brikk.ducklake.deployment.owner")) and (
                    labels.get("dev.brikk.ducklake.deployment.volume") == spec["plugin_volume"] or
                    any(m.get("Name") == spec["plugin_volume"] for m in instance.get("Mounts", []))))

    data_sql = ("mysql" in args or "psql" in args) and any(re.search(
        r"gen_random_uuid\s*\(|\b(?:create|drop)\s+(?:catalog|database|table)\b|\b(?:insert\s+into|delete\s+from|alter\s+table)\b",
        value, re.IGNORECASE) for value in args)
    allocation = args[0] == "run" or args[0] == "compose" and "run" in args or args[0] == "exec" and data_sql
    allowed = not allocation
    if args[0] == "compose":
        allowed = allowed and "-f" in args and str(Path(args[args.index("-f") + 1]).resolve()) in spec["compose_files"]
        allowed = allowed and any(operation in args for operation in ("up", "config"))
    elif args[0] in {"start", "stop", "rm"}:
        allowed = allowed and owned(args[-1])
    elif args[0] == "exec":
        allowed = allowed and owned(args[1])
    elif args[0] == "cp":
        endpoints = [value.split(":", 1)[0] for value in args[1:3] if not value.startswith("/") and ":" in value]
        allowed = allowed and all(owned(value) for value in endpoints)
    elif args[0] == "create":
        mounts = [args[i + 1] for i, value in enumerate(args[:-1]) if value == "-v"]
        allowed = allowed and all(value.startswith(spec["plugin_volume"] + ":") for value in mounts)
        if "--name" in args:
            allowed = allowed and args[args.index("--name") + 1] == spec["claim_name"]
    elif args[:2] == ["volume", "create"]:
        allowed = allowed and args[-1] == spec["plugin_volume"]
    elif args[0] in {"container", "volume"}:
        allowed = allowed and args[1] in {"ls", "inspect"}
    elif args[0] == "image":
        allowed = allowed and args[1] == "inspect"
    else:
        allowed = False

    record = {"argv": args, "allocation_attempt": allocation, "scope_allowed": allowed}
    with Path(spec["log"]).open("a") as stream:
        stream.write(json.dumps(record) + "\n")
    if not allowed:
        print("Acceptance scope guard refused an allocation or foreign target", file=sys.stderr)
        return 97
    if args[0] == "exec" and "mysql" in args and any("information_schema.extensions" in a for a in args):
        result = subprocess.run([real, *args], text=True, capture_output=True)
        if result.returncode == 0 and spec.get("inject_stale"):
            rows = [line.split("\t") for line in result.stdout.splitlines()]
            if len(rows) != 1 or rows[0] != ["ducklake", "CONNECTOR", spec["expected_actual"], "EXTERNAL"]:
                print("Unexpected real inventory at fault-injection boundary", file=sys.stderr)
                return 98
            with Path(spec["log"]).open("a") as stream:
                stream.write(json.dumps({"injected_stale": True, "real_inventory": rows,
                                         "returned_version": spec["stale_version"]}) + "\n")
            rows[0][2] = spec["stale_version"]
            sys.stdout.write("\t".join(rows[0]) + "\n")
        else:
            sys.stdout.write(result.stdout)
        sys.stderr.write(result.stderr)
        return result.returncode
    return subprocess.run([real, *args]).returncode


if __name__ == "__main__":
    sys.exit(main())
