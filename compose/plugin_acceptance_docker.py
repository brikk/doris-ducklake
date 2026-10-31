"""Test-only real-Docker recorder/guard and narrow stale-inventory injection.

No production bypass flag is introduced. Normal replies are passed through;
negative acceptance substitutes only the version in a verified real SQL row.
"""

import json
import hashlib
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
    full_smoke = bool(spec.get("allow_owned_lake"))
    allowed = not allocation or full_smoke
    if args[0] == "compose":
        allowed = allowed and "-f" in args and str(Path(args[args.index("-f") + 1]).resolve()) in spec["compose_files"]
        allowed = allowed and (any(operation in args for operation in ("up", "config")) or
                               full_smoke and "run" in args and args[-1] == "bootstrap")
    elif args[0] in {"start", "stop", "rm"}:
        allowed = allowed and owned(args[-1])
    elif args[0] == "exec":
        allowed = allowed and owned(args[1])
        if allocation and full_smoke:
            if "psql" in args:
                sql = args[args.index("-c") + 1] if "-c" in args else ""
                database = args[args.index("-d") + 1] if "-d" in args else ""
                token = database.removeprefix("doris_smoke_")
                schedule_fixture = (re.fullmatch(r"doris_smoke_[0-9a-f]{32}", database) is not None and
                                    sql.startswith("INSERT INTO ducklake_files_scheduled_for_deletion ") and
                                    f"VALUES (900001,'s3://ducklake/doris-smoke/{token}/ducklake-smoke-cleanup.parquet',false," in sql)
                allowed = allowed and ("gen_random_uuid()" in sql or re.fullmatch(
                    r"CREATE DATABASE doris_smoke_[0-9a-f]{32} TEMPLATE template0;?", sql) is not None or schedule_fixture)
            elif "mysql" in args:
                sql = args[args.index("-e") + 1]
                targets = re.findall(r"(?:CREATE|DROP)\s+(?:CATALOG|DATABASE|TABLE)\s+([^\s(;]+)|(?:INSERT INTO|ALTER TABLE)\s+([^\s(;]+)", sql, re.IGNORECASE)
                allowed = allowed and all(re.match(r"doris_smoke_[0-9a-f]{32}(?:\.|$)", a or b) for a, b in targets)
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
    elif args[0] == "run":
        env = dict(value.split("=", 1) for i, value in enumerate(args) if i and args[i - 1] == "-e" and "=" in value)
        allowed = allowed and full_smoke and "--network" in args and args[args.index("--network") + 1] == spec.get("network")
        if env.get("PG_HOST"):
            token = env.get("SMOKE_RUN_ID", "")
            allowed = allowed and env["PG_HOST"] == spec["postgres"] and env.get("S3_ENDPOINT") == spec["minio"] + ":9000" and bool(re.fullmatch(r"[0-9a-f]{32}", token)) and env.get("PG_DB") == "doris_smoke_" + token and env.get("DATA_PATH") == f"s3://ducklake/doris-smoke/{token}/"
        else:
            allowed = allowed and env.get("S3_HOST") == spec["minio"]
    elif args[0] in {"container", "volume"}:
        allowed = allowed and args[1] in {"ls", "inspect"}
    elif args[0] == "image":
        allowed = allowed and args[1] == "inspect"
    else:
        allowed = False

    recorded = []
    for arg in args:
        if arg.partition("=")[0] in {"PG_PASSWORD", "S3_SECRET", "SMOKE_PG_PASSWORD", "SMOKE_S3_SECRET"}:
            recorded.append(arg.partition("=")[0] + "=<redacted>")
        elif "metadata.password" in arg or "CREATE SECRET" in arg:
            recorded.append("<credential-bearing SQL sha256=" + hashlib.sha256(arg.encode()).hexdigest() + ">")
        else:
            recorded.append(arg)
    record = {"argv": recorded, "allocation_attempt": allocation, "scope_allowed": allowed}
    with Path(spec["log"]).open("a") as stream:
        stream.write(json.dumps(record) + "\n")
    if not allowed:
        print("Acceptance scope guard refused an allocation or foreign target", file=sys.stderr)
        return 97
    if args[0] == "exec" and "mysql" in args and spec.get("inject_bad_rows") and any(
            "JSON_ARRAY(id,name)" in a and re.search(r"\.tpch\.doris_w\b", a) for a in args):
        result = subprocess.run([real, *args], text=True, capture_output=True)
        rows = [json.loads(line) for line in result.stdout.splitlines()] if result.returncode == 0 else []
        if rows != [[1, "alice"], [2, "bob"], [3, "charlie"]]:
            print("Unexpected real typed rows at fault-injection boundary", file=sys.stderr)
            return 98
        changed = [list(row) for row in rows]
        changed[1][1] = "CORRUPTED-SAME-COUNT"
        with Path(spec["log"]).open("a") as stream:
            stream.write(json.dumps({"injected_bad_rows": True, "real_rows": rows, "returned_rows": changed}) + "\n")
        for row in changed:
            print(json.dumps(row))
        return 0
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
