"""Oracle-only diagnostics on an immutable corpus snapshot; does not deploy or qualify Doris."""
import argparse
import csv
import hashlib
import json
from pathlib import Path
import signal
import subprocess
import tarfile
import tempfile
import uuid
import xml.etree.ElementTree as ET

from corpus_acceptance import gradle_init


def selection_from_failures(path):
    with Path(path).open(newline="") as stream:
        rows = list(csv.DictReader(stream))
    names = sorted({row["file"] for row in rows})
    if not names or any(Path(name).is_absolute() or ".." in Path(name).parts
                        or Path(name).suffix not in (".test", ".test_slow") for name in names):
        raise ValueError("Failure index contains no usable corpus selection or an unsafe path")
    return names


def sha256(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def baseline_measurements(path):
    receipt = json.loads(Path(path).read_text())
    if receipt["schema"] != "doris-oracle-baseline-v1" or receipt["doris_mirrors"] != 0:
        raise ValueError("Not an oracle-only baseline")
    present = [file["path"] for file in receipt["files"]]
    missing = receipt["missing_files"]
    if not any(file["outcomes"] for file in receipt["files"]):
        raise ValueError("No executed oracle records; missing/skipped files are not a baseline")
    if len(set(present + missing)) != len(present + missing) or set(present + missing) != set(receipt["selected"]):
        raise ValueError("Incomplete or duplicate baseline coverage")
    actual = sum(outcome["kind"] == "Fail" for file in receipt["files"] for outcome in file["outcomes"])
    if actual != receipt["failed"]:
        raise ValueError("Baseline record failures were lost")
    incomplete = [file["path"] for file in receipt["files"]
                  if (file["file_skip_reason"] or "").startswith(("TIMEOUT:", "BLOCKED:", "CRASH:", "RESOURCE LIMIT:"))]
    return {"selected": len(receipt["selected"]), "present": len(present), "missing": missing,
            "ran_files": receipt["ran_files"], "failed_records": actual, "passed_records": receipt["passed"],
            "skipped_records": receipt["skipped_records"], "incomplete_files": incomplete,
            "qualification": "oracle-only diagnostic, not Doris coverage"}


def run(args):
    project = Path(__file__).resolve().parent.parent
    args.evidence_root.mkdir(parents=True, exist_ok=True)
    root = Path(tempfile.mkdtemp(prefix="baseline-", dir=args.evidence_root)).resolve()
    checkout = args.corpus_checkout.resolve()
    commit = subprocess.check_output(["git", "-C", str(checkout), "rev-parse", args.corpus_ref + "^{commit}"], text=True).strip()
    selection = selection_from_failures(args.failure_index)
    (root / "selection.txt").write_text("\n".join(selection) + "\n")
    snapshot = root / "corpus-snapshot"
    snapshot.mkdir()
    archive = root / "corpus.tar"
    with archive.open("wb") as stream:
        subprocess.run(["git", "-C", str(checkout), "archive", commit, "test/sql", "data"], stdout=stream, check=True)
    with tarfile.open(archive) as source:
        source.extractall(snapshot, filter="data")
    scope = "doris-baseline-" + uuid.uuid4().hex[:12] + ".scope"
    result = {"schema": "doris-corpus-baseline-campaign-v1", "corpus_commit": commit, "axis": args.axis,
              "source_failure_index_sha256": sha256(args.failure_index), "source_failure_index": str(args.failure_index.resolve()),
              "corpus_archive_sha256": sha256(archive), "selection": selection,
              "resources": {"scope": scope, "memory_max": "6G", "swap_max": 0, "cpu_quota": "300%", "workers": 1},
              "source_sha256": {str(path.relative_to(project)): sha256(path) for directory in ("test", "src", "read-tiers")
                                for path in (project / directory).rglob("*.kt")}}
    for name in ("build.gradle.kts", "gradle/libs.versions.toml", "compose/corpus_baseline.py"):
        result["source_sha256"][name] = sha256(project / name)
    properties = {"baseline.corpus.root": str(snapshot / "test/sql"), "baseline.output": str(root),
                  "baseline.axis": args.axis, "baseline.selection": str(root / "selection.txt"),
                  "java.io.tmpdir": str(root / "tmp"), "ducklake.corpus.duckdb.root": str(checkout / "duckdb")}
    (root / "tmp").mkdir()
    init = root / "baseline.init.gradle"
    init.write_text(gradle_init(project, root / "build", properties, task="corpusBaselineTest"))
    state = subprocess.run(["systemctl", "--user", "show", scope, "-p", "LoadState", "--value"], text=True, capture_output=True)
    if state.stdout.strip() != "not-found":
        raise ValueError("Scope exists; refuse adoption")
    command = ["systemd-run", "--user", "--scope", "--quiet", "--unit=" + scope,
               "-p", "MemoryMax=6G", "-p", "MemorySwapMax=0", "-p", "CPUQuota=300%",
               str(project / "gradlew"), "--project-cache-dir", str(root / "gradle-cache"), "-I", str(init),
               "corpusBaselineTest", "--no-daemon", "--max-workers=1", "-PcatalogReadCorrectnessCandidate=false",
               "-Pkotlin.incremental=false", "-Pkotlin.compiler.execution.strategy=in-process",
               "-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=768m"]
    receipt = root / "campaign.json"
    receipt.write_text(json.dumps(result, indent=2) + "\n")
    print("Oracle baseline evidence: " + str(root), flush=True)
    try:
        with (root / "gradle.log").open("w") as log:
            result["gradle_exit"] = subprocess.run(command, cwd=project, stdout=log, stderr=subprocess.STDOUT, timeout=7200).returncode
        result["measurements"] = baseline_measurements(root / "baseline.json")
        xmls = list((root / "build/test-results/corpusBaselineTest").glob("TEST-*.xml"))
        if len(xmls) != 1:
            raise ValueError("Missing explicit baseline JUnit result")
        suite = ET.parse(xmls[0]).getroot()
        counts = {key: int(suite.get(key, 0)) for key in ("tests", "failures", "errors", "skipped")}
        result["junit"] = counts
        if counts["tests"] != 1 or counts["skipped"] or counts["errors"] or result["measurements"]["incomplete_files"]:
            raise ValueError("Baseline did not complete its diagnostic selection")
        if result["gradle_exit"] not in (0, 1) or bool(counts["failures"]) != bool(result["measurements"]["failed_records"]):
            raise ValueError("Build/test failure is not explained by the recorded baseline failures")
        if any(sha256(project / name) != digest for name, digest in result["source_sha256"].items()):
            raise ValueError("Source changed during baseline")
        result["status"] = "diagnostic-complete"
    except BaseException as error:
        result.update(status="failed", error=str(error))
        raise
    finally:
        state = subprocess.run(["systemctl", "--user", "show", scope, "-p", "LoadState", "--value"], text=True, capture_output=True)
        if state.stdout.strip() != "not-found":
            subprocess.run(["systemctl", "--user", "stop", scope], check=True, capture_output=True, timeout=120)
        result["scope_stopped"] = True
        receipt.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result["measurements"], indent=2), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus-checkout", type=Path, required=True)
    parser.add_argument("--corpus-ref", required=True)
    parser.add_argument("--failure-index", type=Path, required=True)
    parser.add_argument("--axis", choices=("embedded", "postgres"), required=True)
    parser.add_argument("--evidence-root", type=Path, required=True)
    run(parser.parse_args())


if __name__ == "__main__":
    def interrupted(_signal, _frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, interrupted)
    main()
