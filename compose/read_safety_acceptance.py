"""Owned, bounded FE/BE proof of local read guards and typed MAP replay (not full corpus)."""
import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import xml.etree.ElementTree as ET

from corpus_acceptance import FullCorpusAcceptance, gradle_init
from plugin_acceptance import write_json
from plugin_preflight import file_hash, require


LIVE_MARKERS = ("historical=refused latest=5000 safe-history=42", "temporal=file+inline-refused varchar=preserved",
                "map mirrors=5/5 varchar=1/1")


def live_receipt(xml, exit_code):
    suite = ET.fromstring(xml)
    counts = {key: int(suite.attrib[key]) for key in ("tests", "failures", "errors", "skipped")}
    markers = {marker: "READ_SAFETY " + marker in xml for marker in LIVE_MARKERS}
    return {"counts": counts, "markers": markers,
            "complete": exit_code == 0 and counts == {"tests": 3, "failures": 0, "errors": 0, "skipped": 0}
                        and all(markers.values())}


class ReadSafetyAcceptance(FullCorpusAcceptance):
    campaign_label = "Read-safety"

    def prepare(self):
        super().prepare()
        self.result["schema"] = "doris-read-safety-live-v1"
        self.result["source_sha256"]["compose/read_safety_acceptance.py"] = file_hash(Path(__file__))

    def replay(self):
        project = self.source.parent
        output = project / "build" / ("read-safety-live-" + self.token)
        output.mkdir(mode=0o700)
        pg_port = self.loopback_port(self.pg, 5432)
        settings = {
            "fe.url": f"jdbc:mysql://127.0.0.1:{self.loopback_port(self.fe, 9030)}/?tinyInt1isBit=true",
            "pg.url": f"jdbc:postgresql://127.0.0.1:{pg_port}/{self.database}",
            "pg.user": "ducklake", "password": self.password, "database": self.database,
            "pg.oracle.hostport": f"host=127.0.0.1 port={pg_port}", "pg.fe.hostport": self.pg + ":5432",
            "data.path": str(self.corpus_tmp / "guard-lake"), "catalog": self.catalog,
            "authored.path": str(self.root / "authored"),
        }
        fixture = self.root / "fixture-private.properties"
        fixture.write_text("".join(f"{key}={value}\n" for key, value in settings.items()))
        fixture.chmod(0o600)
        init = self.root / "live-private.init.gradle"
        init.write_text(gradle_init(project, output, {"java.io.tmpdir": str(self.corpus_tmp)}, task="readSafetyLiveTest"))
        init.chmod(0o600)
        self.scope = "doris-safety-live-" + self.token + ".scope"
        state = subprocess.run(["systemctl", "--user", "show", self.scope, "-p", "LoadState", "--value"],
                               text=True, capture_output=True)
        require(state.stdout.strip() == "not-found", "Live scope exists; refuse adoption")
        command = ["systemd-run", "--user", "--scope", "--quiet", "--unit=" + self.scope,
                   "-p", "MemoryMax=6G", "-p", "MemorySwapMax=0", "-p", "CPUQuota=300%",
                   str(project / "gradlew"), "--project-cache-dir", str(self.root / "gradle-project-cache"),
                   "-I", str(init), "readSafetyLiveTest", "-PcatalogReadCorrectnessCandidate=false",
                   "--no-daemon", "--max-workers=1", "-Pkotlin.incremental=false",
                   "-Pkotlin.compiler.execution.strategy=in-process",
                   "-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=768m"]
        self.result.update(build_directory=str(output), resources={"scope": self.scope, "memory_max": "6G",
                           "swap_max": 0, "cpu_quota": "300%", "parallel_campaigns": 1})
        write_json(self.root / "acceptance.json", self.result)
        log = self.root / "live-jvm.log"
        log.touch(mode=0o600)
        env = dict(os.environ, DORIS_READ_SAFETY_FIXTURE=str(fixture))
        with log.open("w") as stream:
            child = subprocess.Popen(command, cwd=project, env=env, stdout=stream, stderr=subprocess.STDOUT, start_new_session=True)
            self.scope_started = True
            try:
                child.wait(timeout=1200)
            except BaseException:
                self.stop_scope()
                try:
                    os.killpg(child.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
                child.wait(timeout=30)
                raise
            finally:
                log.write_text(log.read_text().replace(self.password, "<redacted>"))
        self.result["gradle_exit"] = child.returncode
        xmls = list((output / "test-results/readSafetyLiveTest").glob("TEST-*.xml"))
        require(len(xmls) == 1, "Missing explicit read-safety XML; no coverage claimed")
        xml = xmls[0].read_text().replace(self.password, "<redacted>")
        xmls[0].write_text(xml)
        (self.root / "readSafetyLiveTest.xml").write_text(xml)
        receipt = live_receipt(xml, child.returncode)
        self.result["live_tests"] = receipt
        require(receipt["complete"],
                "Read-safety live checks failed; inspect redacted live-jvm.log/XML")
        require(all(file_hash(project / name) == digest for name, digest in self.result["source_sha256"].items()),
                "Read-safety source changed during live validation")
        require(all(file_hash(self.source / name) == digest for name, digest in self.result["copied_tooling_sha256"].items()),
                "Deployment tooling changed during live validation")
        require(all(file_hash(project / "ducklake/test/sql" / name) == digest
                    for name, digest in self.result["corpus_sha256"].items()), "Corpus SQL changed during live validation")
        require(not self.corpus_status(), "Corpus checkout changed during live validation")
        self.result["status"] = "passed"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("bundle-a", "bundle-b", "duckdb", "evidence-root"):
        parser.add_argument("--" + name, type=Path, required=True)
    args = parser.parse_args()
    args.evidence_root.mkdir(parents=True, exist_ok=True)
    fixture = ReadSafetyAcceptance(args.bundle_a, args.bundle_b, args.duckdb, args.evidence_root)
    try:
        fixture.run()
    except Exception as error:
        print("Read-safety acceptance error: " + str(error).replace(fixture.password, "<redacted>"), flush=True)
        return 1
    print("PASS bounded read-safety acceptance: " + str(fixture.root), flush=True)
    return 0


if __name__ == "__main__":
    def interrupted(_signal, _frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, interrupted)
    raise SystemExit(main())
