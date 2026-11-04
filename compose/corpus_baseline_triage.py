"""Join retained mirror failures to oracle-only evidence, without erasing or reclassifying them as passes."""
import argparse
from collections import Counter
import csv
import hashlib
import json
from pathlib import Path
import re

from corpus_baseline import baseline_measurements


def outcomes(receipt, file, line):
    return [outcome for entry in receipt["files"] if entry["path"] == file
            for outcome in entry["outcomes"] if outcome["line"] == line]


def state(items):
    if not items:
        return "unobserved"
    kinds = {item["kind"] for item in items}
    return next(iter(kinds)) if len(kinds) == 1 else "mixed"


def classify(row, embedded, postgres):
    reason = row["reason"]
    if row["stage"] == "doris-mirror-divergence":
        return "doris-value-divergence"
    if row["stage"] == "doris-adapter-connect":
        return "adapter-connection-configuration"
    if row["stage"] == "format-inspection":
        return "replay-format-inspection"
    if row["stage"] == "doris-mirror-error":
        if "Historical reads with delete files" in reason or "temporal domain" in reason:
            return "doris-safety-refusal"
        if "nested legacy-file mappings" in reason or "historical nested file" in reason:
            return "doris-nested-read-refusal"
        if row["file"].startswith("encryption/") and "Parquet footer" in reason:
            return "doris-encrypted-read-error"
        if "Unknown column 'snapshot_id'" in reason:
            return "doris-virtual-column-error"
        return "doris-other-mirror-error"
    if postgres == "Fail" and embedded == "Fail":
        return "native-or-replay-baseline"
    if postgres == "Fail" and embedded == "Pass":
        return "metadata-backend-axis"
    if postgres == embedded == "Pass":
        # An independent rerun does not by itself prove why the live run failed.
        return "live-only-or-nondeterministic"
    return "unresolved-baseline-evidence"


def sql_key(sql):
    # These are equivalent SLT placeholder spellings, NOT general SQL normalization.
    return re.sub(r"\$\{([A-Za-z_][A-Za-z0-9_]*)\}", r"{\1}", (sql or "").replace("__TEST_DIR__", "{TEST_DIR}")).strip()


def counterpart(original, producer, file):
    if file in producer["missing_files"]:
        return "file-absent"
    sqls = {sql_key(item.get("sql")) for item in original if item.get("sql")}
    if len(sqls) != 1:
        return "unmapped"
    matches = [item for entry in producer["files"] if entry["path"] == file for item in entry["outcomes"]
               if item.get("sql") and sql_key(item["sql"]) in sqls]
    return state(matches) if len(matches) == 1 else "unmapped"


def load_baseline(path, axis, commit, source_hashes=None):
    path = Path(path)
    measurements = baseline_measurements(path / "baseline.json")
    campaign = json.loads((path / "campaign.json").read_text())
    receipt = json.loads((path / "baseline.json").read_text())
    if campaign.get("status") != "diagnostic-complete" or measurements["incomplete_files"]:
        raise ValueError("Incomplete diagnostic campaign")
    if campaign["corpus_commit"] != commit or receipt["axis"] != axis:
        raise ValueError("Wrong corpus/backend baseline")
    if source_hashes is not None and any(source_hashes[file["path"]] != file["sha256"] for file in receipt["files"]):
        raise ValueError("Baseline corpus bytes differ from retained mirror evidence")
    return receipt


def triage(rows, embedded, postgres, producer_embedded=None, producer_postgres=None):
    joined = []
    for row in rows:
        file, line = row["file"], int(row["line"])
        native = outcomes(embedded, file, line)
        pg = outcomes(postgres, file, line)
        result = {key: row[key] for key in ("file", "line", "stage", "reason")}
        result.update(embedded=state(native), postgres=state(pg),
                      classification=classify(row, state(native), state(pg)),
                      embedded_reason="\n".join(item.get("reason") or "" for item in native),
                      postgres_reason="\n".join(item.get("reason") or "" for item in pg))
        if producer_embedded is not None and producer_postgres is not None:
            result.update(producer_embedded=counterpart(native, producer_embedded, file),
                          producer_postgres=counterpart(pg, producer_postgres, file))
        joined.append(result)
    return joined


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mirror-evidence", type=Path, required=True)
    parser.add_argument("--embedded", type=Path, required=True)
    parser.add_argument("--postgres", type=Path, required=True)
    parser.add_argument("--producer-embedded", type=Path)
    parser.add_argument("--producer-postgres", type=Path)
    parser.add_argument("--producer-commit")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    mirror = json.loads((args.mirror_evidence / "acceptance.json").read_text())
    with (args.mirror_evidence / "failure-index.csv").open(newline="") as stream:
        rows = list(csv.DictReader(stream))
    if len(rows) != mirror["records"]["failed"]:
        raise ValueError("Retained failure count differs from its receipt")
    embedded = load_baseline(args.embedded, "embedded", mirror["corpus_commit"], mirror["corpus_sha256"])
    postgres = load_baseline(args.postgres, "postgres", mirror["corpus_commit"], mirror["corpus_sha256"])
    producer = []
    if args.producer_embedded or args.producer_postgres:
        if not all((args.producer_embedded, args.producer_postgres, args.producer_commit)):
            raise ValueError("Both producer backend axes and their commit are required")
        producer = [load_baseline(args.producer_embedded, "embedded", args.producer_commit),
                    load_baseline(args.producer_postgres, "postgres", args.producer_commit)]
    receipts = [embedded, postgres, *producer]
    if any(receipt["runtime"] != embedded["runtime"] or receipt["selected"] != embedded["selected"] for receipt in receipts):
        raise ValueError("Runtime or selected paths changed between baseline axes")
    result = triage(rows, embedded, postgres, *producer)
    args.output.mkdir(parents=True, exist_ok=True)
    with (args.output / "triage.csv").open("w", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(result[0]))
        writer.writeheader()
        writer.writerows(result)
    summary = {"schema": "doris-baseline-triage-v1", "retained_record_failures": len(rows),
               "classifications": dict(sorted(Counter(row["classification"] for row in result).items())),
               "original_qualification": mirror["status"], "original_results_unchanged": True,
               "limit": "Diagnostic classification, not conformance or proof of each root cause",
               "inputs_sha256": {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in (
                   args.mirror_evidence / "failure-index.csv", args.embedded / "baseline.json", args.postgres / "baseline.json",
                   *[path / "baseline.json" for path in (args.producer_embedded, args.producer_postgres) if path is not None])}}
    (args.output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
