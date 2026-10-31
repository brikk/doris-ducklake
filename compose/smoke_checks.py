"""F25: required assertion ledger and owner/build/run-bound correctness result.

Fail fast after recording a bad check; never continue destructive setup after an
unexpected error. A zero child exit without this complete proof is not success.
"""

import argparse
from datetime import datetime, timezone
from decimal import Decimal
import hashlib
import json
import os
from pathlib import Path
import re
import sys

from plugin_preflight import require


SCOPE = "doris-smoke-supported-api24-v1"
REQUIRED = (
    "tpch_counts", "tpch_sample", "nullable_counts", "nullable_rows", "filter_plan", "filter_rows", "delete_file", "delete_count", "delete_rows",
    "ddl_schema_create", "ddl_columns", "ddl_empty", "ddl_insert", "ddl_bucket", "ddl_drop", "w2_rows", "w2_files",
    "w2c_rows", "w2c_buckets", "w2c_pruning", "ctas_rows", "ctas_files", "defaults", "expiry", "cleanup", "orphans",
    "gc_referenced", "fixture_cleanup",
)
# No reviewed expected failure applies to the current exact FE/BE pin. In
# particular, the historical COUNT(col) comment cannot excuse arbitrary results.
# Future entries must match exact case, FE+BE source lists AND exact observation;
# even expected failures remain non-green/nonzero, rather than correctness passes.
KNOWN_FAILURES = ()
THREE = [[1, "alice"], [2, "bob"], [3, "charlie"]]
POLICY = {
    "tpch_counts": ("eq", [15000, 60175]), "nullable_counts": ("eq", [4, 2]),
    "nullable_rows": ("eq", [[1, 10], [2, None], [3, 30], [4, None]]), "filter_plan": ("eq", True),
    "delete_file": ("ge", 1), "delete_count": ("eq", 93), "ddl_schema_create": ("eq", 1),
    "ddl_columns": ("eq", ["id", "name"]), "ddl_empty": ("eq", []),
    "ddl_insert": ("eq", [[1, "alice"], [2, "bob"]]), "ddl_bucket": ("eq", ["bucket(4)"]), "ddl_drop": ("eq", 0),
    "w2_rows": ("eq", THREE), "w2_files": ("ge", 1), "w2c_rows": ("eq", THREE),
    "w2c_buckets": ("eq", [1, 2, 3]), "w2c_pruning": ("eq", 1), "ctas_rows": ("eq", THREE), "ctas_files": ("ge", 1),
    "defaults": ("eq", [[1, 42], [2, 42], [3, 42], [4, 99]]),
    "expiry": ("expiry", None), "cleanup": ("cleanup", None), "orphans": ("orphans", None),
    "gc_referenced": ("eq", THREE), "fixture_cleanup": ("eq", True),
}


def timestamp():
    return datetime.now(timezone.utc).isoformat()


def read_json(text):
    def reject(value):
        raise ValueError(f"Non-finite JSON number: {value}")
    return json.loads(text, parse_float=Decimal, parse_constant=reject)


def serializable(value):
    if isinstance(value, Decimal):
        return {"decimal": str(value)}
    if isinstance(value, list):
        return [serializable(v) for v in value]
    if isinstance(value, dict):
        return {k: serializable(v) for k, v in value.items()}
    return value


def exact(actual, expected):
    if type(actual) is not type(expected):
        return False
    if isinstance(expected, list):
        return len(actual) == len(expected) and all(exact(a, e) for a, e in zip(actual, expected))
    if isinstance(expected, dict):
        return actual.keys() == expected.keys() and all(exact(actual[k], expected[k]) for k in expected)
    return actual == expected


def integer(value):
    if re.fullmatch(r"[0-9]+", value) is None:
        raise ValueError("Expected an unsigned integer result")
    return int(value)


def evaluate(operator, actual, expected):
    if operator == "eq":
        return exact(actual, expected)
    if operator == "ge":
        return type(actual) is int and type(expected) is int and actual >= expected
    if operator == "expiry":
        return (isinstance(actual, dict) and all(type(actual.get(k)) is int for k in ("expired", "before", "after")) and
                actual["expired"] >= 1 and actual["after"] >= 1 and
                actual["before"] - actual["after"] == actual["expired"])
    if operator == "cleanup":
        return (isinstance(actual, dict) and type(actual.get("deleted")) is int and actual["deleted"] >= 1 and
                type(actual.get("failed")) is int and actual["failed"] == 0 and
                actual.get("blob") == "gone" and type(actual.get("schedule")) is int and actual["schedule"] == 0)
    if operator == "orphans":
        return (isinstance(actual, dict) and type(actual.get("deleted")) is int and actual["deleted"] >= 1 and
                type(actual.get("failed")) is int and actual["failed"] == 0 and
                actual.get("orphan") == "gone" and actual.get("success") == actual.get("foreign") == "present")
    raise ValueError("Unknown assertion operator")


def validate_policy(case, operator, expected):
    if case in {"tpch_sample", "delete_rows", "filter_rows"}:
        count = 5 if case == "tpch_sample" else 93
        require(operator == "eq" and isinstance(expected, list) and
                (0 < len(expected) < 100 if case == "filter_rows" else len(expected) == count) and
                all(isinstance(row, list) and len(row) == 2 and type(row[0]) is int and type(row[1]) is str for row in expected),
                "Malformed native-oracle reference rows")
    else:
        require(case in POLICY and operator == POLICY[case][0] and exact(expected, POLICY[case][1]),
                "Assertion differs from its required case policy")


def known_failure(case, actual, runtime_sources, entries=KNOWN_FAILURES):
    return next((entry for entry in entries if entry["case"] == case and
                 entry["runtime_sources"] == runtime_sources and exact(entry["actual"], actual)), None)


def write(path, value):
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(serializable(value), indent=2, sort_keys=True, allow_nan=False) + "\n")
    temporary.replace(path)


def context(state_path):
    state = json.loads(state_path.read_text())
    require(state.get("deployment_attested") and state.get("loaded_provider_verified"), "Smoke requires deployment attestation")
    require(state.get("mode") == "full", "Correctness checks require full mode")
    return state, state_path.parent / "smoke-assertions.json"


def initialize(state_path, run_id):
    state, path = context(state_path)
    require(re.fullmatch(r"[0-9a-f]{32}", run_id), "Invalid smoke run ID")
    require(not path.exists(), "Assertion ledger already exists; never adopt another run")
    write(path, {"schema": "doris-smoke-assertions-v1", "scope": SCOPE, "owner": state["owner"], "run_id": run_id,
                 "build_id": state["artifact"]["build_id"], "runtime_sources": state["runtime_sources"],
                 "smoke_target": state["smoke_target"],
                 "required": list(REQUIRED), "status": "checking", "checks": [], "started_at": timestamp()})


def record(state_path, case, operator, expected, actual):
    state, path = context(state_path)
    ledger = json.loads(path.read_text())
    require(ledger["owner"] == state["owner"] and ledger["build_id"] == state["artifact"]["build_id"], "Foreign assertion ledger")
    require(ledger["status"] == "checking" and case in REQUIRED and
            case not in [row["case"] for row in ledger["checks"]], "Duplicate/unknown check or closed ledger")
    validate_policy(case, operator, expected)
    passed = evaluate(operator, actual, expected)
    exception = None if passed else known_failure(case, actual, state["runtime_sources"])
    outcome = "passed" if passed else "expected_failure" if exception else "failed"
    ledger["checks"].append({"case": case, "operator": operator, "expected": expected, "actual": actual,
                             "outcome": outcome, "at": timestamp(), **({"known_failure": exception["issue"]} if exception else {})})
    if not passed:
        ledger["status"] = outcome
    write(path, ledger)
    require(passed, f"Smoke {case} {outcome}: expected {serializable(expected)!r}, actual {serializable(actual)!r}")


def abort(state_path, phase, code):
    _, path = context(state_path)
    if path.exists():
        ledger = json.loads(path.read_text())
        ledger.update(status="failed", aborted_phase=phase, exit_code=code, finished_at=timestamp())
        write(path, ledger)


def validate_ledger(ledger, state):
    require(ledger.get("schema") == "doris-smoke-assertions-v1" and ledger.get("scope") == SCOPE, "Invalid assertion schema/scope")
    require(ledger["owner"] == state["owner"] and ledger["build_id"] == state["artifact"]["build_id"] and
            ledger["runtime_sources"] == state["runtime_sources"] and ledger["smoke_target"] == state["smoke_target"],
            "Assertion owner/build/runtime/target mismatch")
    require(re.fullmatch(r"[0-9a-f]{32}", ledger["run_id"]), "Invalid assertion run ID")
    require(ledger["required"] == list(REQUIRED), "Changed mandatory assertion list")
    rows = ledger["checks"]
    require(len(rows) == len(REQUIRED) and {row["case"] for row in rows} == set(REQUIRED), "Missing/duplicate mandatory assertions")
    for row in rows:
        validate_policy(row["case"], row["operator"], row["expected"])
        require(row["outcome"] == "passed" and evaluate(row["operator"], row["actual"], row["expected"]),
                "Required assertion did not pass")


def finish(state_path):
    state, path = context(state_path)
    ledger = json.loads(path.read_text())
    require(ledger["status"] == "checking", "Failed/aborted ledger cannot finish")
    validate_ledger(ledger, state)
    ledger.update(status="passed", finished_at=timestamp())
    write(path, ledger)
    receipt = {"schema": "doris-smoke-correctness-v1", "scope": SCOPE, "owner": state["owner"], "run_id": ledger["run_id"],
               "build_id": ledger["build_id"], "runtime_sources": ledger["runtime_sources"],
               "qualification": state["qualification"], "checks_passed": len(REQUIRED), "checks_required": len(REQUIRED),
               "smoke_target": state["smoke_target"],
               "policy_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
               "ledger_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
               "smoke_correctness_passed": True, "f25_complete": True, "finished_at": timestamp()}
    write(path.parent / "smoke-correctness.json", receipt)


def verify_completed(state_path):
    state, ledger_path = context(state_path)
    ledger = json.loads(ledger_path.read_text())
    require(ledger["status"] == "passed", "No completed correctness ledger")
    validate_ledger(ledger, state)
    receipt = json.loads((ledger_path.parent / "smoke-correctness.json").read_text())
    require(receipt.get("schema") == "doris-smoke-correctness-v1" and receipt.get("scope") == SCOPE and
            receipt["owner"] == ledger["owner"] and receipt["run_id"] == ledger["run_id"] and
            receipt["build_id"] == ledger["build_id"] and receipt["runtime_sources"] == ledger["runtime_sources"] and
            receipt["smoke_target"] == state["smoke_target"] and
            receipt["policy_sha256"] == hashlib.sha256(Path(__file__).read_bytes()).hexdigest() and
            receipt["checks_passed"] == receipt["checks_required"] == len(REQUIRED) and
            receipt["ledger_sha256"] == hashlib.sha256(ledger_path.read_bytes()).hexdigest() and
            receipt["smoke_correctness_passed"] is True and receipt["f25_complete"] is True,
            "Correctness receipt is incomplete, stale or foreign")
    return receipt


def object_state(text, exit_code, listing=None, listing_exit_code=None):
    payload = json.loads(text)
    if exit_code == 0:
        require(payload.get("status") == "success", "Object stat did not report success")
        return "present"
    codes = set()

    def walk(value):
        if isinstance(value, dict):
            for key, item in value.items():
                if key.lower() == "code" and isinstance(item, str):
                    codes.add(item)
                walk(item)
        elif isinstance(value, list):
            for item in value:
                walk(item)
    walk(payload)
    explicit_missing = bool(codes.intersection({"NoSuchKey", "NoSuchObject"}))
    error = payload.get("error") or {}
    cause = error.get("cause") or {}
    # The pinned mc normalizes S3 missing-key errors to this code-free JSON
    # shape. Never trust its text alone: independently require a successful
    # empty exact-path listing. Auth/network/bucket errors still fail closed.
    confirmed_normalized_missing = (not codes and error.get("type") == "fatal" and
        cause.get("message") == "Object does not exist" and cause.get("error") == {} and
        listing_exit_code == 0 and listing is not None and not listing.strip())
    require(payload.get("status") == "error" and (explicit_missing or confirmed_normalized_missing),
            "Object stat failed for a reason other than independently confirmed absence")
    return "gone"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=["init", "eq", "ge", "json", "rows", "stats", "plan", "fixture", "catalog-sql",
                                                "procedure", "finish", "abort", "object-state"])
    parser.add_argument("--state", type=Path)
    parser.add_argument("--case")
    parser.add_argument("--operator", default="eq")
    parser.add_argument("--expected")
    parser.add_argument("--actual", nargs="*", default=[])
    parser.add_argument("--run-id")
    parser.add_argument("--exit-code", type=int)
    parser.add_argument("--listing-exit-code", type=int)
    parser.add_argument("--listing-json")
    args = parser.parse_args()
    try:
        if args.operation == "init":
            initialize(args.state, args.run_id)
        elif args.operation == "finish":
            finish(args.state)
        elif args.operation == "abort":
            abort(args.state, args.case, args.exit_code)
        elif args.operation == "object-state":
            print(object_state(sys.stdin.read(), args.exit_code, args.listing_json, args.listing_exit_code))
        elif args.operation == "fixture":
            from smoke_rows import FIXTURES
            require(args.expected in FIXTURES, "Unknown expected row fixture")
            print(json.dumps(FIXTURES[args.expected]))
        elif args.operation == "catalog-sql":
            from smoke_lake import require_smoke_lake, doris_literal
            require_smoke_lake()
            name = "doris_smoke_" + os.environ["SMOKE_RUN_ID"]
            properties = {"type": "ducklake", "metadata.url": f"jdbc:postgresql://{os.environ['SMOKE_PG_CONTAINER']}:5432/{os.environ['PG_DB']}",
                          "metadata.user": os.environ["SMOKE_PG_USER"], "metadata.password": os.environ["SMOKE_PG_PASSWORD"],
                          "storage.warehouse": os.environ["DATA_PATH"], "s3.endpoint": "http://" + os.environ["SMOKE_MINIO_CONTAINER"] + ":9000",
                          "s3.region": "us-east-1", "s3.access_key": os.environ["SMOKE_S3_KEY_ID"],
                          "s3.secret_key": os.environ["SMOKE_S3_SECRET"], "use_path_style": "true", "maintenance.min-retention": "1s"}
            require(args.expected in {"create", "recreate"}, "Unknown catalog operation")
            print((f"DROP CATALOG {name};\n" if args.expected == "recreate" else "") + f"CREATE CATALOG {name} PROPERTIES (" +
                  ",".join(doris_literal(k) + "=" + doris_literal(v) for k, v in properties.items()) + ");")
        elif args.operation == "plan":
            text = sys.stdin.read()
            # Internal node/predicate spellings are not a SQL result contract.
            # Require a successful nonempty table scan plan; filter_rows then
            # proves the actual bounded predicate result against native DuckDB.
            valid = bool(text.strip()) and "scan" in text.lower() and "orders" in text.lower()
            record(args.state, args.case, "eq", True, valid)
        elif args.operation == "stats":
            fields = {"expiry": ("expired", "before", "after"), "cleanup": ("deleted", "failed", "blob", "schedule"),
                      "orphans": ("deleted", "failed", "orphan", "success", "foreign")}[args.case]
            require(len(args.actual) == len(fields), "Missing procedure/state counters")
            numeric = {"expired", "before", "after", "deleted", "failed", "schedule"}
            actual = {k: integer(v) if k in numeric else v for k, v in zip(fields, args.actual)}
            record(args.state, args.case, args.case, None, actual)
        elif args.operation in {"eq", "ge"}:
            expected = [integer(v) for v in args.expected.split(",")]
            actual = [integer(v) for v in args.actual]
            record(args.state, args.case, "ge" if args.operation == "ge" else "eq",
                   expected[0] if len(expected) == 1 else expected, actual[0] if len(actual) == 1 else actual)
        elif args.operation == "rows":
            actual = [read_json(line) for line in sys.stdin.read().splitlines()]
            expected = read_json(args.expected)
            if isinstance(expected, dict):
                require(expected.get("query") == args.case and expected.get("duckdb_version") == "1.5.6",
                        "Wrong native-oracle reference identity")
                expected = expected["rows"]
            record(args.state, args.case, "eq", expected, actual)
        elif args.operation == "procedure":
            values = args.actual[0].split("\t")
            require(values[0].lower() in {"false", "0"}, "Expected an actual, non-dry-run procedure result")
            if args.expected == "expiry":
                require(len(values) == 5 and values[1] == "catalog-wide (ignores the named table)", "Unexpected expiry result schema/scope")
                count = integer(values[2])
                integer(values[3])
                ids = [integer(v) for v in values[4].split(",")] if values[4] else []
                require(len(ids) == len(set(ids)) == count, "Expiry IDs/count differ")
                print(f"{count}\t0")
            else:
                require(args.expected in {"cleanup", "orphans"} and len(values) == 4 and values[1] == "1s", "Unexpected file-GC result schema/retention")
                print(f"{integer(values[2])}\t{integer(values[3])}")
        else:
            record(args.state, args.case, args.operator, read_json(args.expected), read_json(args.actual[0]))
    except (ValueError, KeyError, TypeError, OSError) as error:
        print(f"[smoke-check] FAIL: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
