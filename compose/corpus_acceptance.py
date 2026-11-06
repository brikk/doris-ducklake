"""Full-directory replay on disposable, owned API27 infrastructure (not fleet conformance)."""
import argparse
import json
import os
from pathlib import Path
import re
import signal
import socket
import subprocess
import xml.etree.ElementTree as ET

from plugin_acceptance import Acceptance, write_json
from plugin_preflight import file_hash, require


CATALOG = "0.17.0"


def selected_hashes(discovered, selection):
    require(selection and len(set(selection)) == len(selection), "Empty/duplicate corpus selection")
    require(all(name in discovered for name in selection), "Selection contains an unknown/unsafe corpus path")
    return {name: discovered[name] for name in selection}


def gradle_init(project, output, properties, task="corpusReplayTest"):
    """Init scripts also run in included builds: guard both hooks by project directory."""
    selected = "rootProject.projectDir == new File(" + json.dumps(str(project)) + ")"
    return (
        "gradle.beforeProject { p -> if (p.projectDir == new File(" + json.dumps(str(project)) +
        ")) p.layout.buildDirectory.set(new File(" + json.dumps(str(output)) + ")) }\n" +
        "gradle.projectsEvaluated { if (" + selected + ") { rootProject.tasks.named(" + json.dumps(task) + ") {\n" +
        "".join("systemProperty " + json.dumps(k) + ", " + json.dumps(v) + "\n" for k, v in properties.items()) +
        "testLogging { events 'started', 'failed', 'skipped'; showStandardStreams = true }\n" +
        "} } }\n"
    )


def replay_measurements(xml, selected_files):
    suite = ET.fromstring(xml)
    counts = {key: int(suite.attrib[key]) for key in ("tests", "failures", "errors", "skipped")}
    require(counts["tests"] == 1 and counts["skipped"] == 0, "Full-corpus test did not execute")
    stdout = "\n".join(node.text or "" for node in suite.findall("system-out"))
    files = re.findall(r"corpus replay: (\d+) files", stdout)
    mirrors = re.findall(r"mirrors: (\d+) attempted, (\d+) passed, (\d+) failed, (\d+) skipped", stdout)
    records = re.findall(r"ran (\d+) \(records: (\d+) passed, (\d+) failed, (\d+) skipped\)", stdout)
    require(files and mirrors and records, "Missing measured corpus/mirror counts")
    require(int(files[-1]) == selected_files, "Full discovery coverage is incomplete")
    measured = dict(zip(("attempted", "passed", "failed", "skipped"), map(int, mirrors[-1])))
    require(measured["passed"] > 0, "No successful Doris mirrors; not counted as conformance")
    return {
        "junit": counts,
        "reported_files": int(files[-1]),
        "records": dict(zip(("ran_files", "passed", "failed", "skipped"), map(int, records[-1]))),
        "mirrors": measured,
        "timed_out_or_blocked": "TIMEOUT:" in stdout or "BLOCKED:" in stdout,
        "format_scope_halts": sum(map(int, re.findall(r"halted mid-file by format scope (\d+)", stdout))),
    }


class FullCorpusAcceptance(Acceptance):
    def prepare(self):
        for artifact in self.artifacts.values():
            catalogs = {name for name in artifact["files"] if name.startswith("lib/ducklake-catalog-")}
            require(catalogs == {f"lib/ducklake-catalog-{CATALOG}.jar"}, "Wrong release catalog bundle")
        super().prepare()
        project = self.source.parent
        config = json.loads(self.doris_file.read_text())
        with socket.socket() as reservation:
            reservation.bind(("127.0.0.1", 0))
            port = reservation.getsockname()[1]
        config["services"]["doris-fe"]["ports"] = [f"127.0.0.1:{port}:9030"]
        self.corpus_tmp = self.root / "corpus"
        self.corpus_tmp.mkdir()
        self.replay_scratch = self.corpus_tmp / "mirror-scratch"
        self.replay_scratch.mkdir()
        fixtures = [p for p in (project / "ducklake/data", project / "ducklake/duckdb/data") if p.is_dir()]
        require(project / "ducklake/data" in fixtures, "Missing native Parquet fixtures")
        for name in ("doris-fe", "doris-be"):
            config["services"][name]["volumes"] += [f"{self.corpus_tmp}:{self.corpus_tmp}"]
            config["services"][name]["volumes"] += [f"{path}:{path}:ro" for path in fixtures]
        write_json(self.doris_file, config)
        corpus = project / "ducklake/test/sql"
        self.result.update(schema="doris-full-corpus-campaign-v1", catalog_version=CATALOG,
                           oracle_setup_format="DUCKLAKE_1_0", readonly_fixture_mounts=list(map(str, fixtures)),
                           execution_format_scope=["DUCKLAKE_1_0"], mirror_format_scope=["DUCKLAKE_1_0"],
                           metadata_rewrite_scope="ALL_ATTACHES", mirror_cap_per_file=40,
                           curated_exclusions_unchanged=True)
        self.result["corpus_commit"] = subprocess.check_output(
            ["git", "-C", str(project / "ducklake"), "rev-parse", "HEAD"], text=True).strip()
        require(not self.corpus_status(), "Corpus checkout is modified; cannot claim its recorded commit")
        self.result["corpus_sha256"] = {str(p.relative_to(corpus)): file_hash(p) for p in sorted(corpus.rglob("*"))
                                       if p.is_file() and p.suffix in (".test", ".test_slow")}
        selection = getattr(self, "selected_files", None)
        if selection is not None:
            self.result["corpus_sha256"] = selected_hashes(self.result["corpus_sha256"], selection)
            self.result.update(schema="doris-selected-corpus-campaign-v1", selected_files=selection)
            (self.root / "selection.txt").write_text("\n".join(selection) + "\n")
        self.result["replay_scratch"] = "owned per-engine/per-target directory outside oracle TEST_DIR/warehouse"
        self.result["source_sha256"] = {str(p.relative_to(project)): file_hash(p)
            for directory in ("src", "test/src")
            for p in (project / directory).rglob("*.kt")}
        for name in ("build.gradle.kts", "gradle/libs.versions.toml", "compose/corpus_acceptance.py"):
            self.result["source_sha256"][name] = file_hash(project / name)

    def corpus_status(self):
        return subprocess.check_output(["git", "-C", str(self.source.parent / "ducklake"), "status", "--porcelain"], text=True)

    def loopback_port(self, container, port):
        bindings = self.data("container", "inspect", container)[0]["NetworkSettings"]["Ports"][f"{port}/tcp"]
        require(len(bindings) == 1 and bindings[0]["HostIp"] == "127.0.0.1", "Unexpected endpoint binding")
        return bindings[0]["HostPort"]

    def stop_scope(self):
        if not getattr(self, "scope_started", False):
            return
        state = subprocess.run(["systemctl", "--user", "show", self.scope, "-p", "LoadState", "--value"],
                               text=True, capture_output=True)
        if state.stdout.strip() != "not-found":
            subprocess.run(["systemctl", "--user", "stop", self.scope], check=True, capture_output=True, timeout=120)
        self.result["scope_stopped"] = True

    def redact_outputs(self, output):
        for path in (self.root / "full-corpus.log", self.root / "corpus-report.txt"):
            if path.is_file():
                path.write_text(path.read_text().replace(self.password, "<redacted>"))
        reports = output / "test-results/corpusReplayTest"
        for path in reports.glob("TEST-*.xml"):
            path.write_text(path.read_text().replace(self.password, "<redacted>"))

    def replay(self):
        project = self.source.parent
        output = project / "build" / (f"catalog-{CATALOG}-full-corpus-" + self.token)
        self.result["build_directory"] = str(output)
        pg_port = self.loopback_port(self.pg, 5432)
        properties = {
            "doris.corpus.fe.url": f"jdbc:mysql://127.0.0.1:{self.loopback_port(self.fe, 9030)}/?user=root&tinyInt1isBit=true",
            "doris.corpus.pg.host.url": f"jdbc:postgresql://127.0.0.1:{pg_port}/{self.database}",
            "doris.corpus.pg.user": "ducklake", "doris.corpus.pg.password": self.password,
            "doris.corpus.pg.oracle.hostport": f"host=127.0.0.1 port={pg_port}",
            "doris.corpus.pg.fe.hostport": self.pg + ":5432", "ducklake.corpus.dirs": "all",
            "doris.corpus.report.path": str(self.root / "corpus-report.txt"),
            "doris.corpus.scratch.root": str(self.replay_scratch),
        }
        if getattr(self, "selected_files", None) is not None:
            properties["ducklake.corpus.files"] = str(self.root / "selection.txt")
        init = self.root / "corpus-private.init.gradle"
        init.write_text(gradle_init(project, output, properties))
        init.chmod(0o600)
        command = [str(project / "gradlew"), "--project-cache-dir", str(self.root / "gradle-project-cache"),
                   "-I", str(init), "corpusReplayTest", "-PdorisCorpusDir=" + str(self.corpus_tmp),
                   "-PcatalogReadCorrectnessCandidate=false", "--no-daemon", "--max-workers=1",
                   "-Pkotlin.incremental=false", "-Pkotlin.compiler.execution.strategy=in-process",
                   "-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=768m"]
        self.scope = "doris-corpus-" + self.token + ".scope"
        state = subprocess.run(["systemctl", "--user", "show", self.scope, "-p", "LoadState", "--value"],
                               text=True, capture_output=True)
        require(state.stdout.strip() == "not-found", "Resource scope already exists; refuse adoption")
        command = ["systemd-run", "--user", "--scope", "--quiet", "--unit=" + self.scope,
                   "-p", "MemoryMax=6G", "-p", "MemorySwapMax=0", "-p", "CPUQuota=300%", *command]
        self.result["resources"] = {"scope": self.scope, "memory_max": "6G", "swap_max": 0,
                                    "cpu_quota": "300%", "gradle_workers": 1, "parallel_campaigns": 1}
        log = self.root / "full-corpus.log"
        log.touch(mode=0o600)
        write_json(self.root / "acceptance.json", self.result)
        with log.open("w") as stream:
            child = subprocess.Popen(command, cwd=project, stdout=stream, stderr=subprocess.STDOUT, start_new_session=True)
            self.scope_started = True
            try:
                child.wait(timeout=21600)
            except BaseException:
                self.stop_scope()
                try:
                    os.killpg(child.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
                try:
                    child.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    os.killpg(child.pid, signal.SIGKILL)
                    child.wait(timeout=30)
                raise
            finally:
                self.redact_outputs(output)
        self.result["gradle_exit"] = child.returncode
        xmls = list((output / "test-results/corpusReplayTest").glob("TEST-*.xml"))
        require(len(xmls) == 1, "Missing full-corpus XML; not counted as coverage")
        xml = xmls[0].read_text()
        (self.root / "corpusReplayTest.xml").write_text(xml)
        self.result.update(replay_measurements(xml, len(self.result["corpus_sha256"])))
        require(all(file_hash(project / name) == digest for name, digest in self.result["source_sha256"].items()),
                "Source changed during full-corpus campaign")
        require(all(file_hash(project / "ducklake/test/sql" / name) == digest
                    for name, digest in self.result["corpus_sha256"].items()), "Corpus SQL changed during campaign")
        require(not self.corpus_status(), "Corpus checkout changed; preserve for inspection")
        if getattr(self, "selected_files", None) is not None:
            require((self.root / "selection.txt").read_text().splitlines() == self.selected_files,
                    "Explicit corpus selection changed during campaign")
        require(all(file_hash(self.source / name) == digest for name, digest in self.result["copied_tooling_sha256"].items()),
                "Deployment tooling changed during campaign")
        require(not any(self.replay_scratch.iterdir()), "Owned replay scratch remains after adapter cleanup")
        self.result["replay_scratch_cleaned"] = True
        counts = self.result["junit"]
        passed = child.returncode == counts["failures"] == counts["errors"] == 0 and not self.result["timed_out_or_blocked"]
        self.result["status"] = "passed" if passed else "failed"

    def run(self):
        print(getattr(self, "campaign_label", "Full-corpus") + " evidence: " + str(self.root), flush=True)
        try:
            self.prepare()
            self.phase("released-provider", "A")
            self.replay()
        except BaseException as error:
            self.result.update(status="interrupted" if isinstance(error, KeyboardInterrupt) else "failed",
                               error=str(error).replace(self.password, "<redacted>"))
            raise
        finally:
            try:
                self.stop_scope()
                self.cleanup()
                if "protected_before" in self.result:
                    self.result["protected_after"] = self.protected()
                    require(self.result["protected_before"] == self.result["protected_after"], "Protected state changed")
            except BaseException as error:
                self.result.update(status="failed", cleanup_error=str(error).replace(self.password, "<redacted>"))
                raise
            finally:
                write_json(self.root / "acceptance.json", self.result)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("bundle-a", "bundle-b", "duckdb", "evidence-root"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--file-list", type=Path, help="Explicit bounded selection; not a full-directory campaign")
    args = parser.parse_args()
    args.evidence_root.mkdir(parents=True, exist_ok=True)
    fixture = FullCorpusAcceptance(args.bundle_a, args.bundle_b, args.duckdb, args.evidence_root)
    if args.file_list is not None:
        fixture.selected_files = args.file_list.read_text().splitlines()
        fixture.campaign_label = "Selected-corpus"
    try:
        fixture.run()
    except Exception as error:
        print("Full-corpus campaign error: " + str(error).replace(fixture.password, "<redacted>"), flush=True)
        return 1
    print(getattr(fixture, "campaign_label", "Full-corpus") + " campaign " + fixture.result["status"] + ": " + str(fixture.root), flush=True)
    return 0 if fixture.result["status"] == "passed" else 1


if __name__ == "__main__":
    def interrupted(_signal, _frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, interrupted)
    raise SystemExit(main())
